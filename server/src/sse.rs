use super::*;
use crate::{http_sse as limits, protocol::EndReason, wire::Out};
use axum::body::Bytes;
use futures_util::Stream;
use std::{
    pin::Pin,
    sync::atomic::{AtomicU64, Ordering},
    task::{Context as TaskContext, Poll},
};

pub enum Event {
    Data(u64, Bytes),
    End(EndReason),
}
pub struct Entry {
    tx: mpsc::Sender<Event>,
    received: u64,
    consumed: Arc<AtomicU64>,
    ended: bool,
}
pub type Streams = Arc<Mutex<HashMap<String, Entry>>>;
pub struct Guard {
    pub id: String,
    pub tunnel: Tunnel,
    pub _permit: Option<tokio::sync::OwnedSemaphorePermit>,
    pub _sse_permit: Option<tokio::sync::OwnedSemaphorePermit>,
    pub cancel_peer: bool,
}
impl Drop for Guard {
    fn drop(&mut self) {
        self.tunnel.pending.lock().unwrap().remove(&self.id);
        self.tunnel.sse_streams.lock().unwrap().remove(&self.id);
        self.tunnel.sse_heads.lock().unwrap().remove(&self.id);
        if self.cancel_peer {
            control(
                &self.tunnel,
                Packet::SseCancel {
                    id: self.id.clone(),
                },
            );
        }
    }
}
pub fn control(tunnel: &Tunnel, packet: Packet) {
    // Never spawn an unbounded collection of Drop tasks or block the reader.
    if tunnel.control.try_send(Out::Packet(packet)).is_err() {
        tunnel.cancel.cancel();
    }
}
pub struct ResponseStream {
    rx: mpsc::Receiver<Event>,
    consumed: Arc<AtomicU64>,
    guard: Option<Guard>,
    cancelled: Pin<Box<dyn Future<Output = ()> + Send>>,
}
impl ResponseStream {
    pub fn new(guard: Guard) -> Self {
        let (tx, rx) = mpsc::channel(limits::WINDOW as usize + 1);
        let consumed = Arc::new(AtomicU64::new(0));
        guard.tunnel.sse_streams.lock().unwrap().insert(
            guard.id.clone(),
            Entry {
                tx,
                received: 0,
                consumed: consumed.clone(),
                ended: false,
            },
        );
        let cancelled = Box::pin(guard.tunnel.cancel.clone().cancelled_owned());
        Self {
            rx,
            consumed,
            guard: Some(guard),
            cancelled,
        }
    }
    fn finish(&mut self, normal: bool) {
        if let Some(mut guard) = self.guard.take() {
            guard.cancel_peer = !normal;
        }
        self.rx.close();
    }
}
impl Stream for ResponseStream {
    type Item = Result<Bytes, std::io::Error>;
    fn poll_next(mut self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<Option<Self::Item>> {
        if self.guard.is_none() {
            return Poll::Ready(None);
        }
        if self.cancelled.as_mut().poll(cx).is_ready() {
            self.finish(false);
            return Poll::Ready(Some(Err(std::io::Error::other("Tunnel disconnected"))));
        }
        match self.rx.poll_recv(cx) {
            Poll::Pending => Poll::Pending,
            Poll::Ready(Some(Event::Data(seq, data))) => {
                self.consumed.store(seq, Ordering::Release);
                let guard = self.guard.as_ref().unwrap();
                control(
                    &guard.tunnel,
                    Packet::SseWindow {
                        id: guard.id.clone(),
                        ack_seq: seq,
                    },
                );
                Poll::Ready(Some(Ok(data)))
            }
            Poll::Ready(Some(Event::End(EndReason::Eof))) => {
                self.finish(true);
                Poll::Ready(None)
            }
            Poll::Ready(Some(Event::End(_))) | Poll::Ready(None) => {
                self.finish(false);
                Poll::Ready(Some(Err(std::io::Error::other("SSE upstream interrupted"))))
            }
        }
    }
}
pub fn dispatch(tunnel: &Tunnel, packet: Packet) -> Result<()> {
    match packet {
        Packet::SseData { id, seq, data } => {
            ensure!(
                data.len() <= limits::FRAME.div_ceil(3) * 4,
                "Oversized SSE data"
            );
            let bytes = STANDARD.decode(data)?;
            ensure!(
                !bytes.is_empty() && bytes.len() <= limits::FRAME,
                "Invalid SSE data size"
            );
            let mut streams = tunnel.sse_streams.lock().unwrap();
            if let Some(entry) = streams.get_mut(&id) {
                ensure!(
                    !entry.ended
                        && seq == entry.received + 1
                        && seq <= entry.consumed.load(Ordering::Acquire) + limits::WINDOW,
                    "Invalid SSE sequence/window"
                );
                entry.received = seq;
                // A compliant sender cannot fill this queue beyond WINDOW data frames.
                if entry
                    .tx
                    .try_send(Event::Data(seq, Bytes::from(bytes)))
                    .is_err()
                {
                    streams.remove(&id);
                    control(tunnel, Packet::SseCancel { id });
                }
            } // Unknown/locally cancelled IDs never create a new stream.
        }
        Packet::SseEnd {
            id,
            last_seq,
            reason,
        } => {
            let mut streams = tunnel.sse_streams.lock().unwrap();
            if let Some(entry) = streams.get_mut(&id) {
                ensure!(
                    !entry.ended && last_seq == entry.received,
                    "Invalid SSE end"
                );
                entry.ended = true;
                if entry.tx.try_send(Event::End(reason)).is_err() {
                    streams.remove(&id);
                }
            }
        }
        _ => anyhow::bail!("Unexpected SSE packet"),
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures_util::StreamExt;
    fn tunnel() -> (Tunnel, crate::wire::Receiver) {
        let (tx, _, control, rx) = crate::wire::channel();
        (
            Tunnel {
                tx,
                control,
                pending: Default::default(),
                slots: Arc::new(Semaphore::new(32)),
                streams: Default::default(),
                cancel: CancellationToken::new(),
                sse_enabled: true,
                sse_slots: Arc::new(Semaphore::new(8)),
                sse_streams: Default::default(),
                sse_heads: Default::default(),
            },
            rx,
        )
    }
    fn body(tunnel: &Tunnel) -> ResponseStream {
        ResponseStream::new(Guard {
            id: "a".repeat(32),
            tunnel: tunnel.clone(),
            _permit: Some(tunnel.slots.clone().try_acquire_owned().unwrap()),
            _sse_permit: Some(tunnel.sse_slots.clone().try_acquire_owned().unwrap()),
            cancel_peer: true,
        })
    }
    fn data(seq: u64) -> Packet {
        Packet::SseData {
            id: "a".repeat(32),
            seq,
            data: STANDARD.encode([0xff, 0x00]),
        }
    }
    #[tokio::test]
    async fn only_body_consumption_returns_credit_and_permits_survive_head() {
        let (tunnel, mut outgoing) = tunnel();
        let mut body = body(&tunnel);
        assert_eq!(tunnel.slots.available_permits(), 31);
        assert_eq!(tunnel.sse_slots.available_permits(), 7);
        for seq in 1..=4 {
            dispatch(&tunnel, data(seq)).unwrap();
        }
        assert!(dispatch(&tunnel, data(5)).is_err());
        assert!(
            tokio::time::timeout(Duration::from_millis(10), outgoing.next())
                .await
                .is_err(),
            "queue insertion must not ACK"
        );
        assert_eq!(
            body.next().await.unwrap().unwrap(),
            Bytes::from_static(&[255, 0])
        );
        assert!(matches!(
            outgoing.next().await,
            Some(Out::Packet(Packet::SseWindow { ack_seq: 1, .. }))
        ));
        dispatch(&tunnel, data(5)).unwrap();
        drop(body);
        assert_eq!(tunnel.slots.available_permits(), 32);
        assert_eq!(tunnel.sse_slots.available_permits(), 8);
        assert!(tunnel.sse_streams.lock().unwrap().is_empty());
        assert!(matches!(
            outgoing.next().await,
            Some(Out::Packet(Packet::SseCancel { .. }))
        ));
        dispatch(&tunnel, data(6)).unwrap(); // late data after cancellation cannot recreate state
        assert!(tunnel.sse_streams.lock().unwrap().is_empty());
    }
    #[tokio::test]
    async fn normal_end_drains_and_invalid_sequences_are_rejected() {
        let (tunnel, _outgoing) = tunnel();
        let mut body = body(&tunnel);
        assert!(dispatch(&tunnel, data(2)).is_err());
        dispatch(&tunnel, data(1)).unwrap();
        assert!(dispatch(&tunnel, data(1)).is_err());
        assert!(
            dispatch(
                &tunnel,
                Packet::SseEnd {
                    id: "a".repeat(32),
                    last_seq: 2,
                    reason: EndReason::Eof
                }
            )
            .is_err()
        );
        dispatch(
            &tunnel,
            Packet::SseEnd {
                id: "a".repeat(32),
                last_seq: 1,
                reason: EndReason::Eof,
            },
        )
        .unwrap();
        assert!(dispatch(&tunnel, data(2)).is_err());
        assert!(body.next().await.unwrap().is_ok());
        assert!(body.next().await.is_none());
        assert_eq!(tunnel.slots.available_permits(), 32);
    }
}
