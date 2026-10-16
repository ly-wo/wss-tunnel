#[path = "../../protocol.rs"]
mod protocol;
use anyhow::{Context, Result, ensure};
use axum::{
    Router,
    body::{Body, to_bytes},
    extract::{
        FromRequestParts, Request, State,
        ws::{Message, WebSocket, WebSocketUpgrade},
    },
    response::{IntoResponse, Response},
};
use base64::{Engine, engine::general_purpose::STANDARD};
use clap::Parser;
use futures_util::{SinkExt, StreamExt};
use http::StatusCode;
use protocol::{MAX_BODY, MAX_PENDING, MAX_WIRE, Packet};
use std::{
    collections::{HashMap, HashSet},
    sync::{Arc, Mutex},
    time::Duration,
};
use subtle::ConstantTimeEq;
use tokio::sync::{Semaphore, mpsc, oneshot};
use uuid::Uuid;
#[derive(Parser)]
struct Args {
    #[arg(long, env = "TUNNEL_DOMAIN")]
    domain: String,
    #[arg(long, env = "CONNECT_DOMAIN")]
    connect_domain: String,
    #[arg(long, env = "TUNNEL_TOKEN_FILE")]
    token_file: std::path::PathBuf,
    #[arg(long, env = "TUNNEL_BIND", default_value = "0.0.0.0:8080")]
    bind: String,
}
type Pending = Arc<Mutex<HashMap<String, oneshot::Sender<Packet>>>>;
#[derive(Clone)]
struct Tunnel {
    tx: mpsc::Sender<Packet>,
    pending: Pending,
    slots: Arc<Semaphore>,
}
struct App {
    domain: String,
    connect_domain: String,
    token: Vec<u8>,
    tunnels: Mutex<HashMap<String, Tunnel>>,
    slots: Arc<Semaphore>,
    names: Mutex<HashSet<String>>,
}
fn reply(status: StatusCode, message: &str) -> Response {
    (status, message.to_owned()).into_response()
}
fn domain_valid(s: &str) -> bool {
    s.len() <= 253
        && s.contains('.')
        && s.split('.').all(|l| {
            !l.is_empty()
                && l.len() <= 63
                && !l.starts_with('-')
                && !l.ends_with('-')
                && l.bytes()
                    .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
        })
}
#[tokio::main]
async fn main() -> Result<()> {
    dotenvy::dotenv().ok();
    let args = Args::parse();
    ensure!(
        domain_valid(&args.domain)
            && (domain_valid(&args.connect_domain) || args.connect_domain == "localhost"),
        "Use lowercase DNS names, without scheme or wildcard"
    );
    let token = std::fs::read_to_string(&args.token_file)
        .context("Read token file")?
        .trim()
        .as_bytes()
        .to_vec();
    ensure!(token.len() >= 32, "Token must contain at least 32 bytes");
    let app = Arc::new(App {
        domain: args.domain,
        connect_domain: args.connect_domain,
        token,
        tunnels: Mutex::new(HashMap::new()),
        slots: Arc::new(Semaphore::new(128)),
        names: Mutex::new(HashSet::new()),
    });
    let listener = tokio::net::TcpListener::bind(&args.bind).await?;
    eprintln!("Listening on {}; TLS terminates at Traefik", args.bind);
    axum::serve(listener, Router::new().fallback(handle).with_state(app))
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await?;
    Ok(())
}
async fn handle(State(app): State<Arc<App>>, req: Request) -> Response {
    let host = req
        .headers()
        .get("host")
        .and_then(|h| h.to_str().ok())
        .unwrap_or("")
        .split(':')
        .next()
        .unwrap_or("")
        .to_ascii_lowercase();
    if host == app.connect_domain {
        if req.uri().path() != "/_tunnel/ws" {
            return reply(StatusCode::NOT_FOUND, "Not found");
        }
        let auth = req
            .headers()
            .get("authorization")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .unwrap_or("");
        if !bool::from(auth.as_bytes().ct_eq(&app.token)) {
            return reply(StatusCode::UNAUTHORIZED, "Invalid token");
        }
        let Ok(permit) = app.slots.clone().try_acquire_owned() else {
            return reply(StatusCode::SERVICE_UNAVAILABLE, "Connection limit reached");
        };
        let query = match axum::extract::Query::<HashMap<String, String>>::try_from_uri(req.uri()) {
            Ok(q) => q.0,
            Err(_) => return reply(StatusCode::BAD_REQUEST, "Invalid query"),
        };
        let requested = query.get("name");
        if requested.is_some_and(|n| !protocol::valid_name(n)) {
            return reply(StatusCode::BAD_REQUEST, "Invalid subdomain name");
        }
        let host = {
            let mut names = app.names.lock().unwrap();
            loop {
                let name = requested
                    .cloned()
                    .unwrap_or_else(|| Uuid::new_v4().simple().to_string()[..16].to_owned());
                let host = format!("{name}.{}", app.domain);
                if host.len() > 253 || host == app.connect_domain {
                    return reply(StatusCode::BAD_REQUEST, "Reserved or invalid subdomain");
                }
                if names.insert(host.clone()) {
                    break host;
                }
                if requested.is_some() {
                    return reply(StatusCode::CONFLICT, "Subdomain is already in use");
                }
            }
        };
        let lease = NameLease {
            app: app.clone(),
            host,
        };
        let (mut parts, _) = req.into_parts();
        return match WebSocketUpgrade::from_request_parts(&mut parts, &()).await {
            Ok(ws) => ws
                .max_message_size(MAX_WIRE)
                .max_frame_size(MAX_WIRE)
                .on_upgrade(move |socket| async move {
                    let _permit = permit;
                    session(app, socket, lease).await;
                }),
            Err(e) => e.into_response(),
        };
    }
    if req.headers().contains_key("upgrade") || req.method() == http::Method::CONNECT {
        return reply(
            StatusCode::NOT_IMPLEMENTED,
            "Only buffered HTTP requests are supported",
        );
    }
    let tunnel = app.tunnels.lock().unwrap().get(&host).cloned();
    let Some(tunnel) = tunnel else {
        return reply(StatusCode::NOT_FOUND, "Tunnel offline or unknown");
    };
    let Ok(_permit) = tunnel.slots.clone().try_acquire_owned() else {
        return reply(StatusCode::SERVICE_UNAVAILABLE, "Tunnel busy");
    };
    let (parts, body) = req.into_parts();
    let data = match tokio::time::timeout(Duration::from_secs(30), to_bytes(body, MAX_BODY)).await {
        Ok(Ok(b)) => b,
        Ok(Err(_)) => {
            return reply(
                StatusCode::PAYLOAD_TOO_LARGE,
                "Request exceeds 5 MiB or body read failed",
            );
        }
        Err(_) => return reply(StatusCode::REQUEST_TIMEOUT, "Body read timed out"),
    };
    let id = Uuid::new_v4().simple().to_string();
    let packet = Packet::Request {
        id: id.clone(),
        method: parts.method.to_string(),
        path: parts
            .uri
            .path_and_query()
            .map(|v| v.as_str())
            .unwrap_or("/")
            .to_string(),
        headers: protocol::headers(&parts.headers),
        body: STANDARD.encode(data),
    };
    let (tx, rx) = oneshot::channel();
    tunnel.pending.lock().unwrap().insert(id.clone(), tx);
    // Always remove the pending sender if this HTTP task is cancelled/disconnected.
    let _guard = PendingGuard {
        id,
        pending: tunnel.pending.clone(),
    };
    if tunnel.tx.try_send(packet).is_err() {
        return reply(StatusCode::BAD_GATEWAY, "Tunnel disconnected or busy");
    }
    match tokio::time::timeout(Duration::from_secs(35), rx).await {
        Ok(Ok(Packet::Response {
            status,
            headers,
            body,
            ..
        })) => {
            let Ok(status) = StatusCode::from_u16(status) else {
                return reply(StatusCode::BAD_GATEWAY, "Invalid status");
            };
            if status.is_informational() {
                return reply(
                    StatusCode::BAD_GATEWAY,
                    "Unsupported informational response",
                );
            }
            let Ok(headers) = protocol::restore(headers) else {
                return reply(StatusCode::BAD_GATEWAY, "Invalid headers");
            };
            let Ok(body) = STANDARD.decode(body) else {
                return reply(StatusCode::BAD_GATEWAY, "Invalid response body");
            };
            if body.len() > MAX_BODY {
                return reply(StatusCode::BAD_GATEWAY, "Response exceeds 5 MiB");
            }
            let mut response = Response::new(Body::from(body));
            *response.status_mut() = status;
            *response.headers_mut() = headers;
            response
        }
        Err(_) => reply(StatusCode::GATEWAY_TIMEOUT, "Local service timed out"),
        _ => reply(StatusCode::BAD_GATEWAY, "Tunnel disconnected"),
    }
}
struct PendingGuard {
    id: String,
    pending: Pending,
}
impl Drop for PendingGuard {
    fn drop(&mut self) {
        self.pending.lock().unwrap().remove(&self.id);
    }
}
// The lease is dropped even if WebSocket upgrade fails, releasing the reservation.
struct NameLease {
    app: Arc<App>,
    host: String,
}
impl Drop for NameLease {
    fn drop(&mut self) {
        self.app.tunnels.lock().unwrap().remove(&self.host);
        self.app.names.lock().unwrap().remove(&self.host);
    }
}
async fn session(app: Arc<App>, socket: WebSocket, lease: NameLease) {
    let (tx, mut rx) = mpsc::channel(MAX_PENDING);
    let pending: Pending = Arc::new(Mutex::new(HashMap::new()));
    let host = lease.host.clone();
    app.tunnels.lock().unwrap().insert(
        host.clone(),
        Tunnel {
            tx,
            pending: pending.clone(),
            slots: Arc::new(Semaphore::new(MAX_PENDING)),
        },
    );
    let (mut sink, mut stream) = socket.split();
    let ready = serde_json::to_string(&Packet::Ready {
        url: format!("https://{host}"),
    })
    .unwrap();
    if sink.send(Message::Text(ready.into())).await.is_ok() {
        let mut heartbeat = tokio::time::interval(Duration::from_secs(20));
        let mut last_seen = tokio::time::Instant::now();
        loop {
            tokio::select! {
                _=heartbeat.tick()=> {
                    if last_seen.elapsed()>Duration::from_secs(60) {break;}
                    if !send(&mut sink,Message::Ping(Vec::new().into())).await {break;}
                }
                Some(packet)=rx.recv()=> {
                    if !send(&mut sink,Message::Text(serde_json::to_string(&packet).unwrap().into())).await {break;}
                }
                message=stream.next()=>match message {
                    Some(Ok(Message::Text(text)))=> {
                        last_seen=tokio::time::Instant::now();
                        match serde_json::from_str::<Packet>(&text) {
                            Ok(packet @ Packet::Response { .. })=> {
                                let Packet::Response { ref id,.. }=packet else { unreachable!() };
                                let tx=pending.lock().unwrap().remove(id);
                                if let Some(tx)=tx {let _=tx.send(packet);}
                            }
                            _=>break,
                        }
                    }
                    Some(Ok(Message::Pong(_)))=>last_seen=tokio::time::Instant::now(),
                    Some(Ok(Message::Ping(p)))=> {if !send(&mut sink,Message::Pong(p)).await {break;}},
                    _=>break,
                }
            }
        }
    }
    app.tunnels.lock().unwrap().remove(&host);
    pending.lock().unwrap().clear();
}
async fn send<S>(sink: &mut S, message: Message) -> bool
where
    S: futures_util::Sink<Message> + Unpin,
{
    matches!(
        tokio::time::timeout(Duration::from_secs(10), sink.send(message)).await,
        Ok(Ok(()))
    )
}
