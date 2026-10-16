use crate::{
    protocol::{MAX_BODY, MAX_PENDING, MAX_WIRE, Packet},
    target::Target,
    tls::client_config_from_path,
    websocket,
};
use anyhow::{Context, Result, bail, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use futures_util::{SinkExt, StreamExt};
use std::{path::PathBuf, time::Duration};
use tokio::sync::mpsc;
use tokio::task::JoinSet;
use tokio_tungstenite::{
    Connector, connect_async_tls_with_config,
    tungstenite::{Message, client::IntoClientRequest, protocol::WebSocketConfig},
};
use tokio_util::sync::CancellationToken;

#[derive(Clone)]
pub enum TunnelAuth {
    Token(String),
    Password { username: String, password: String },
}

impl std::fmt::Debug for TunnelAuth {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TunnelAuth::Token(_) => f.write_str("Token([redacted])"),
            TunnelAuth::Password { username, .. } => f
                .debug_struct("Password")
                .field("username", username)
                .field("password", &"[redacted]")
                .finish(),
        }
    }
}

#[derive(Clone)]
pub struct TunnelConfig {
    pub server: String,
    pub target: String,
    pub name: Option<String>,
    pub auth: TunnelAuth,
    pub ca_cert_path: Option<PathBuf>,
    pub local_ca_cert_path: Option<PathBuf>,
    pub allow_insecure_auth: bool,
}

impl std::fmt::Debug for TunnelConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TunnelConfig")
            .field("server", &self.server)
            .field("target", &self.target)
            .field("name", &self.name)
            .field("auth", &self.auth)
            .field("ca_cert_path", &self.ca_cert_path)
            .field("local_ca_cert_path", &self.local_ca_cert_path)
            .field("allow_insecure_auth", &self.allow_insecure_auth)
            .finish()
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TunnelPhase {
    Connecting,
    Ready,
    Stopping,
    Stopped,
    Failed,
}

#[derive(Clone, Debug)]
pub struct TunnelEvent {
    pub generation: u64,
    pub phase: TunnelPhase,
    pub public_url: Option<String>,
    pub target_display: Option<String>,
    pub message: Option<String>,
}

fn failure(id: String, status: u16, message: &str) -> Packet {
    Packet::Response {
        id,
        status,
        headers: vec![],
        body: STANDARD.encode(message),
    }
}

async fn forward(http: reqwest::Client, target: Target, packet: Packet) -> Packet {
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
    async fn run(
        http: reqwest::Client,
        target: Target,
        method: String,
        path: String,
        headers: Vec<(String, String)>,
        body: String,
    ) -> Result<(u16, Vec<(String, String)>, Vec<u8>)> {
        ensure!(
            path.starts_with('/') && !path.starts_with("//"),
            "Invalid request target"
        );
        let data = STANDARD.decode(body)?;
        ensure!(data.len() <= MAX_BODY, "Request too large");
        let method = reqwest::Method::from_bytes(method.as_bytes())?;
        ensure!(method != reqwest::Method::CONNECT, "CONNECT unsupported");
        let url = target.url(&path, false)?;
        let response = http
            .request(method, url)
            .headers(crate::protocol::restore(headers)?)
            .body(data)
            .send()
            .await?;
        let status = response.status().as_u16();
        let headers = crate::protocol::headers(response.headers());
        ensure!(
            response.content_length().unwrap_or(0) <= MAX_BODY as u64,
            "Response too large"
        );
        let mut stream = response.bytes_stream();
        let mut body = Vec::new();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk?;
            ensure!(body.len() + chunk.len() <= MAX_BODY, "Response too large");
            body.extend_from_slice(&chunk);
        }
        Ok((status, headers, body))
    }
    match tokio::time::timeout(
        Duration::from_secs(30),
        run(http, target, method, path, headers, body),
    )
    .await
    {
        Ok(Ok((status, headers, body))) => Packet::Response {
            id,
            status,
            headers,
            body: STANDARD.encode(body),
        },
        Ok(Err(e)) => {
            eprintln!("Local request failed: {e}");
            failure(id, 502, "Local service unavailable or invalid response")
        }
        Err(_) => failure(id, 504, "Local service timed out"),
    }
}

fn emit(
    generation: u64,
    events: &mpsc::UnboundedSender<TunnelEvent>,
    phase: TunnelPhase,
    public_url: Option<String>,
    target_display: Option<String>,
    message: Option<String>,
) {
    let _ = events.send(TunnelEvent {
        generation,
        phase,
        public_url,
        target_display,
        message,
    });
}

pub async fn run_tunnel(
    generation: u64,
    config: TunnelConfig,
    events: mpsc::UnboundedSender<TunnelEvent>,
    cancel: CancellationToken,
) -> Result<()> {
    emit(
        generation,
        &events,
        TunnelPhase::Connecting,
        None,
        None,
        None,
    );

    let run_result: Result<()> = async {
    let target = Target::new(&config.target)?;
    let mut url = crate::target::server_url(&config.server)?;
    if let Some(name) = &config.name {
        ensure!(
            crate::protocol::valid_name(name),
            "Name must be 1-63 lowercase letters/digits/hyphens, with no leading or trailing hyphen"
        );
        url.query_pairs_mut().append_pair("name", name);
    }

    let authorization = match &config.auth {
        TunnelAuth::Password { username, password } => {
            ensure!(
                url.scheme() == "wss" || config.allow_insecure_auth,
                "Password connections require WSS; use allow_insecure_auth only for local testing"
            );
            ensure!(
                !username.contains(':') && !username.is_empty(),
                "Invalid username"
            );
            format!(
                "Basic {}",
                STANDARD.encode(format!("{username}:{password}"))
            )
        }
        TunnelAuth::Token(token) => {
            ensure!(
                token.trim().len() >= 32,
                "Token must contain at least 32 bytes"
            );
            format!("Bearer {}", token.trim())
        }
    };

    let mut request = url.as_str().into_client_request()?;
    request
        .headers_mut()
        .insert("authorization", authorization.parse()?);

    let tls = client_config_from_path(config.ca_cert_path.as_deref())?;
    let local_tls = client_config_from_path(config.local_ca_cert_path.as_deref())?;

    let mut ws_config = WebSocketConfig::default();
    ws_config.max_message_size = Some(MAX_WIRE);
    ws_config.max_frame_size = Some(MAX_WIRE);

    let connect = connect_async_tls_with_config(
        request,
        Some(ws_config),
        false,
        Some(Connector::Rustls(tls)),
    );

    let (socket, _) = tokio::select! {
        _ = cancel.cancelled() => return Ok(()),
        result = tokio::time::timeout(Duration::from_secs(15), connect) => {
            result.context("WebSocket tunnel connection timed out")??
        }
    };

    let mut http = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .no_proxy()
        .timeout(Duration::from_secs(30));
    if let Some(path) = config.local_ca_cert_path.as_deref() {
        let mut reader = std::io::BufReader::new(std::fs::File::open(path)?);
        for cert in rustls_pemfile::certs(&mut reader) {
            http = http.add_root_certificate(reqwest::Certificate::from_der(cert?.as_ref())?);
        }
    }
    let http = http.build()?;
    let streams = websocket::Streams::default();
    let (mut sink, mut stream) = socket.split();

    let first = tokio::select! {
        _ = cancel.cancelled() => {
            let _ = tokio::time::timeout(Duration::from_secs(2), sink.close()).await;
            return Ok(());
        }
        result = tokio::time::timeout(Duration::from_secs(10), stream.next()) => {
            result?
        }
    };

    let public_url = match first {
        Some(Ok(Message::Text(text))) => match serde_json::from_str::<Packet>(&text)? {
            Packet::Ready { url } => url,
            _ => bail!("Unexpected handshake"),
        },
        _ => bail!("Server did not assign a public URL"),
    };

    emit(
        generation,
        &events,
        TunnelPhase::Ready,
        Some(public_url.clone()),
        Some(target.display().to_owned()),
        None,
    );

    let (tx, mut rx) = mpsc::channel::<Packet>(MAX_PENDING);
    let mut jobs = JoinSet::new();
    let mut last_seen = tokio::time::Instant::now();
    let mut heartbeat = tokio::time::interval(Duration::from_secs(20));

    let loop_result: Result<()> = async {
        loop {
            tokio::select! {
                _ = cancel.cancelled() => break,
                _ = heartbeat.tick() => {
                    ensure!(last_seen.elapsed() < Duration::from_secs(60), "Server heartbeat timed out");
                    tokio::time::timeout(Duration::from_secs(10), sink.send(Message::Ping(Vec::new().into()))).await??;
                }
                Some(_) = jobs.join_next(), if !jobs.is_empty() => {}
                Some(packet) = rx.recv() => {
                    tokio::time::timeout(Duration::from_secs(10), sink.send(Message::Text(serde_json::to_string(&packet)?.into()))).await??;
                }
                message = stream.next() => match message {
                    Some(Ok(Message::Text(text))) => {
                        last_seen = tokio::time::Instant::now();
                        let packet = serde_json::from_str::<Packet>(&text)?;
                        if let Packet::Request { ref id, .. } = packet {
                            if jobs.len() >= MAX_PENDING {
                                let response = failure(id.clone(), 503, "Local concurrency limit reached");
                                tokio::time::timeout(Duration::from_secs(10), sink.send(Message::Text(serde_json::to_string(&response)?.into()))).await??;
                            } else {
                                let http = http.clone();
                                let tx = tx.clone();
                                let target = target.clone();
                                jobs.spawn(async move {
                                    let result = forward(http, target, packet).await;
                                    let _ = tx.send(result).await;
                                });
                            }
                        } else if let Packet::WsOpen { ref id, .. } = packet {
                            if jobs.len() >= MAX_PENDING {
                                let response = Packet::WsOpened {
                                    id: id.clone(),
                                    status: 503,
                                    headers: vec![],
                                    protocol: None,
                                };
                                tokio::time::timeout(Duration::from_secs(10), sink.send(Message::Text(serde_json::to_string(&response)?.into()))).await??;
                            } else {
                                let (in_tx, in_rx) = websocket::channel();
                                ensure!(!streams.lock().unwrap().contains_key(id), "Duplicate WebSocket stream id");
                                streams.lock().unwrap().insert(id.clone(), in_tx);
                                jobs.spawn(websocket::bridge(target.clone(), local_tls.clone(), packet, streams.clone(), tx.clone(), in_rx));
                            }
                        } else if let Packet::WsFrame { id, frame } = packet {
                            websocket::dispatch(&streams, &id, frame);
                        } else if let Packet::WsClose { id } = packet {
                            streams.lock().unwrap().remove(&id);
                        } else {
                            bail!("Unexpected server packet");
                        }
                    }
                    Some(Ok(Message::Ping(p))) => {
                        tokio::time::timeout(Duration::from_secs(10), sink.send(Message::Pong(p))).await??;
                    }
                    Some(Ok(Message::Pong(_))) => last_seen = tokio::time::Instant::now(),
                    Some(Ok(Message::Close(_))) | None => bail!("Tunnel disconnected"),
                    Some(Err(e)) => return Err(e.into()),
                    _ => bail!("Unsupported WebSocket message"),
                }
            }
        }
        Ok(())
    }
    .await;
    loop_result?;

    jobs.abort_all();
    let _ = tokio::time::timeout(Duration::from_secs(2), sink.close()).await;
    Ok(())
    }
    .await;

    if cancel.is_cancelled() {
        emit(generation, &events, TunnelPhase::Stopped, None, None, None);
        return Ok(());
    }

    match run_result {
        Ok(()) => {
            emit(generation, &events, TunnelPhase::Stopped, None, None, None);
            Ok(())
        }
        Err(e) => {
            let msg = e.to_string();
            emit(
                generation,
                &events,
                TunnelPhase::Failed,
                None,
                None,
                Some(msg),
            );
            Err(e)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn token_auth_requires_length_and_emits_failed() {
        let cfg = TunnelConfig {
            server: "https://example.com".into(),
            target: "http://127.0.0.1:1".into(),
            name: None,
            auth: TunnelAuth::Token("short".into()),
            ca_cert_path: None,
            local_ca_cert_path: None,
            allow_insecure_auth: false,
        };
        let (tx, mut rx) = mpsc::unbounded_channel();
        let cancel = CancellationToken::new();
        let rt = tokio::runtime::Runtime::new().unwrap();
        let err = rt
            .block_on(run_tunnel(1, cfg, tx, cancel))
            .expect_err("should fail");
        assert!(err.to_string().contains("32"));
        let failed = rx.blocking_recv().expect("connecting");
        assert_eq!(failed.phase, TunnelPhase::Connecting);
        let failed = rx.blocking_recv().expect("failed event");
        assert_eq!(failed.phase, TunnelPhase::Failed);
    }
}
