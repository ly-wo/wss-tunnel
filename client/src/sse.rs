use crate::{
    core::failure,
    http_sse as limits,
    protocol::{self, EndReason, Packet},
    target::Target,
};
use anyhow::{Result, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use futures_util::StreamExt;
use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::sync::{Notify, Semaphore, mpsc};
use tokio::time::{Instant, timeout, timeout_at};
use tokio_util::sync::CancellationToken;

#[derive(Clone, Debug)]
pub struct SseConfig {
    pub enabled: bool,
    pub max_streams: usize,
    pub idle_timeout: Duration,
    pub backpressure_timeout: Duration,
}
impl Default for SseConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            max_streams: 8,
            idle_timeout: Duration::from_secs(45),
            backpressure_timeout: Duration::from_secs(60),
        }
    }
}
#[derive(Default)]
struct Credit {
    head: bool,
    opened: bool,
    issued: u64,
    sent: u64,
    acked: u64,
}
pub struct Flow {
    credit: Mutex<Credit>,
    changed: Notify,
    cancel: CancellationToken,
}
impl Flow {
    fn acknowledge(&self, ack: u64) -> Result<()> {
        let mut c = self.credit.lock().unwrap();
        ensure!(c.head && ack <= c.sent, "Invalid SSE acknowledgement");
        if !c.opened {
            ensure!(ack == 0, "Invalid initial SSE window");
            c.opened = true;
        }
        c.acked = c.acked.max(ack);
        self.changed.notify_one();
        Ok(())
    }
    async fn capacity(&self, duration: Duration) -> Result<(), EndReason> {
        timeout(duration, async {
            loop {
                let changed = self.changed.notified();
                {
                    let c = self.credit.lock().unwrap();
                    if c.opened && c.issued - c.acked < limits::WINDOW {
                        return;
                    }
                }
                changed.await;
            }
        })
        .await
        .map_err(|_| EndReason::BackpressureTimeout)
    }
    fn issue(&self) -> u64 {
        let mut c = self.credit.lock().unwrap();
        c.issued += 1;
        c.issued
    }
}
type Flows = Arc<Mutex<HashMap<String, Arc<Flow>>>>;
pub struct Guard {
    id: String,
    flows: Flows,
    flow: Arc<Flow>,
}
impl Drop for Guard {
    fn drop(&mut self) {
        self.flow.cancel.cancel();
        self.flows.lock().unwrap().remove(&self.id);
    }
}
#[derive(Clone)]
pub struct Runtime {
    pub http: reqwest::Client,
    pub target: Target,
    pub settings: SseConfig,
    pub data: mpsc::Sender<Packet>,
    pub reply: mpsc::Sender<Packet>,
    pub parent: CancellationToken,
    flows: Flows,
    slots: Arc<Semaphore>,
}
impl Runtime {
    pub fn new(
        http: reqwest::Client,
        target: Target,
        settings: SseConfig,
        data: mpsc::Sender<Packet>,
        reply: mpsc::Sender<Packet>,
        parent: CancellationToken,
    ) -> Self {
        Self {
            slots: Arc::new(Semaphore::new(settings.max_streams)),
            flows: Default::default(),
            http,
            target,
            settings,
            data,
            reply,
            parent,
        }
    }
    pub fn register(&self, id: &str) -> Result<Guard> {
        let mut flows = self.flows.lock().unwrap();
        ensure!(!flows.contains_key(id), "Duplicate request id");
        let flow = Arc::new(Flow {
            credit: Default::default(),
            changed: Notify::new(),
            cancel: self.parent.child_token(),
        });
        flows.insert(id.into(), flow.clone());
        Ok(Guard {
            id: id.into(),
            flows: self.flows.clone(),
            flow,
        })
    }
    pub fn cancel(&self, id: &str) {
        if let Some(f) = self.flows.lock().unwrap().get(id) {
            f.cancel.cancel();
        }
    }
    pub fn ack(&self, id: &str, ack: u64) -> Result<()> {
        if let Some(f) = self.flows.lock().unwrap().get(id) {
            f.acknowledge(ack)?;
        }
        Ok(())
    }
    // Called by the sole writer, before handing the frame to the socket.
    pub fn sending(&self, packet: &Packet) -> bool {
        if let Packet::SseData { id, seq, .. } = packet {
            let flows = self.flows.lock().unwrap();
            if let Some(f) = flows.get(id) {
                if f.cancel.is_cancelled() {
                    return false;
                }
                f.credit.lock().unwrap().sent = *seq;
            }
        }
        true
    }
    pub async fn forward(self, packet: Packet, guard: Guard) {
        let started = Instant::now();
        let id = guard.id.clone();
        let outcome = tokio::select! {
            biased;
            _ = guard.flow.cancel.cancelled() => "cancelled",
            result = self.run(packet, &guard.flow) => if result.is_ok() { "finished" } else { "transport_error" },
        };
        eprintln!(
            "request_id={id} route=http_candidate elapsed_ms={} end={outcome}",
            started.elapsed().as_millis()
        );
    }
    async fn run(&self, packet: Packet, flow: &Flow) -> Result<()> {
        let Packet::Request {
            id,
            method,
            path,
            headers,
            body,
        } = packet
        else {
            unreachable!()
        };
        let started = Instant::now();
        let deadline = started + Duration::from_secs(30);
        let prepare = async {
            ensure!(
                path.starts_with('/') && !path.starts_with("//"),
                "Invalid target"
            );
            let body = STANDARD.decode(body)?;
            ensure!(body.len() <= protocol::MAX_BODY, "Request too large");
            let headers = protocol::restore(headers)?;
            let method = reqwest::Method::from_bytes(method.as_bytes())?;
            let response = self
                .http
                .request(method, self.target.url(&path, false)?)
                .headers(headers.clone())
                .body(body)
                .send()
                .await?;
            Ok::<_, anyhow::Error>((headers, response))
        };
        let (request_headers, response) = match timeout_at(deadline, prepare).await {
            Ok(Ok(value)) => value,
            result => {
                let status = if result.is_err() { 504 } else { 502 };
                self.reply
                    .send(failure(
                        id,
                        status,
                        "Local service unavailable or timed out",
                    ))
                    .await?;
                return Ok(());
            }
        };
        let status = response.status().as_u16();
        let headers = protocol::headers(response.headers());
        if !limits::selected(&request_headers, status, response.headers()) {
            let buffered = async {
                ensure!(
                    response.content_length().unwrap_or(0) <= protocol::MAX_BODY as u64,
                    "Response too large"
                );
                let mut stream = response.bytes_stream();
                let mut body = Vec::new();
                while let Some(chunk) = stream.next().await {
                    let chunk = chunk?;
                    ensure!(
                        body.len() + chunk.len() <= protocol::MAX_BODY,
                        "Response too large"
                    );
                    body.extend_from_slice(&chunk);
                }
                Ok::<_, anyhow::Error>(body)
            };
            let reply = match timeout_at(deadline, buffered).await {
                Ok(Ok(body)) => Packet::Response {
                    id,
                    status,
                    headers,
                    body: STANDARD.encode(body),
                },
                Ok(Err(_)) => failure(id, 502, "Local service unavailable or invalid response"),
                Err(_) => failure(id, 504, "Local service timed out"),
            };
            self.reply.send(reply).await?;
            return Ok(());
        }
        let Ok(_permit) = self.slots.clone().try_acquire_owned() else {
            self.reply
                .send(failure(id, 503, "SSE stream limit reached"))
                .await?;
            return Ok(());
        };
        let head = Packet::SseHead {
            id: id.clone(),
            status,
            headers: headers.clone(),
        };
        if !limits::valid_head(&headers) || serde_json::to_vec(&head)?.len() > limits::HEAD_WIRE {
            self.reply
                .send(failure(id, 502, "Invalid SSE response headers"))
                .await?;
            return Ok(());
        }
        flow.credit.lock().unwrap().head = true;
        self.data.send(head).await?;
        let mut stream = response.bytes_stream();
        let reason = 'read: loop {
            if let Err(reason) = flow.capacity(self.settings.backpressure_timeout).await {
                break reason;
            }
            let chunk = match timeout(self.settings.idle_timeout, async {
                loop {
                    match stream.next().await {
                        Some(Ok(bytes)) if bytes.is_empty() => continue,
                        item => return item,
                    }
                }
            })
            .await
            {
                Err(_) => break EndReason::IdleTimeout,
                Ok(Some(Err(_))) => break EndReason::ReadError,
                Ok(None) => break EndReason::Eof,
                Ok(Some(Ok(chunk))) => chunk,
            };
            for bytes in chunk.chunks(limits::FRAME) {
                if let Err(reason) = flow.capacity(self.settings.backpressure_timeout).await {
                    break 'read reason;
                }
                let seq = flow.issue();
                self.data
                    .send(Packet::SseData {
                        id: id.clone(),
                        seq,
                        data: STANDARD.encode(bytes),
                    })
                    .await?;
            }
        };
        let last_seq = flow.credit.lock().unwrap().issued;
        eprintln!(
            "request_id={id} route=sse upstream_status=200 elapsed_ms={} end={reason:?} frames={last_seq}",
            started.elapsed().as_millis()
        );
        self.data
            .send(Packet::SseEnd {
                id,
                last_seq,
                reason,
            })
            .await?;
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn cumulative_window_cannot_inflate_and_stops_reads() {
        let flow = Flow {
            credit: Mutex::new(Credit {
                head: true,
                ..Default::default()
            }),
            changed: Notify::new(),
            cancel: CancellationToken::new(),
        };
        flow.acknowledge(0).unwrap();
        for seq in 1..=4 {
            flow.capacity(Duration::from_millis(10)).await.unwrap();
            assert_eq!(flow.issue(), seq);
            flow.credit.lock().unwrap().sent = seq;
        }
        flow.acknowledge(0).unwrap();
        assert_eq!(
            flow.capacity(Duration::from_millis(10)).await,
            Err(EndReason::BackpressureTimeout)
        );
        assert!(flow.acknowledge(5).is_err());
        flow.acknowledge(1).unwrap();
        flow.acknowledge(1).unwrap();
        flow.capacity(Duration::from_millis(10)).await.unwrap();
        flow.issue();
        assert_eq!(
            flow.capacity(Duration::from_millis(10)).await,
            Err(EndReason::BackpressureTimeout)
        );
    }
}
