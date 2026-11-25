mod auth;
#[path = "../../http_sse.rs"]
mod http_sse;
#[path = "../../protocol.rs"]
mod protocol;
mod request_meta;
mod sse;
mod websocket;
#[path = "../../wire.rs"]
mod wire;
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
    collections::HashMap,
    sync::{Arc, Mutex},
    time::Duration,
};
use subtle::ConstantTimeEq;
use tokio::sync::{Semaphore, mpsc, oneshot};
use tokio_util::sync::CancellationToken;
use uuid::Uuid;
#[derive(Parser)]
struct Args {
    #[arg(long, env="TUNNEL_HTTP_SSE", default_value="auto", value_parser=["auto", "off"])]
    http_sse: String,
    #[arg(long, env="TUNNEL_SSE_MAX_STREAMS", default_value="8", value_parser=clap::value_parser!(u16).range(1..=8))]
    sse_max_streams: u16,
    #[arg(long, env="TUNNEL_PUBLIC_SCHEME", default_value="https", value_parser=["http","https"])]
    public_scheme: String,
    #[arg(long, env = "TUNNEL_DOMAIN")]
    domain: String,
    #[arg(long, env = "CONNECT_DOMAIN")]
    connect_domain: String,
    #[arg(long, env = "TUNNEL_TOKEN_FILE")]
    token_file: Option<std::path::PathBuf>,
    #[arg(long, env = "TUNNEL_ENABLE_LEGACY_TOKEN", default_value = "true", action = clap::ArgAction::Set)]
    enable_legacy_token: bool,
    #[arg(long, env = "TUNNEL_DATABASE", default_value = "data/auth.sqlite3")]
    database: std::path::PathBuf,
    #[arg(long, env = "TUNNEL_ALLOW_REGISTRATION", default_value = "true", action = clap::ArgAction::Set)]
    allow_registration: bool,
    #[arg(long, env = "TUNNEL_BIND", default_value = "0.0.0.0:8080")]
    bind: String,
    #[arg(long, env = "TUNNEL_TRUSTED_PROXIES", value_delimiter = ',')]
    trusted_proxies: Vec<String>,
    #[arg(long)]
    bootstrap_admin: Option<String>,
    #[arg(long, requires = "bootstrap_admin")]
    bootstrap_password_file: Option<std::path::PathBuf>,
    #[arg(long, requires = "bootstrap_admin")]
    bootstrap_only: bool,
}
type Pending = Arc<Mutex<HashMap<String, oneshot::Sender<Packet>>>>;
#[derive(Clone)]
struct Tunnel {
    sse_enabled: bool,
    sse_slots: Arc<Semaphore>,
    sse_streams: sse::Streams,
    sse_heads: Arc<Mutex<std::collections::HashSet<String>>>,
    control: mpsc::Sender<wire::Out>,
    tx: mpsc::Sender<Packet>,
    pending: Pending,
    slots: Arc<Semaphore>,
    streams: websocket::Streams,
    cancel: CancellationToken,
}
struct Connection {
    user_id: Option<i64>,
    cancel: CancellationToken,
}
struct App {
    http_sse: bool,
    sse_max_streams: usize,
    domain: String,
    connect_domain: String,
    public_scheme: String,
    token: Vec<u8>,
    auth: auth::Auth,
    trusted_proxies: Vec<ipnet::IpNet>,
    domain_changes: Arc<tokio::sync::Mutex<()>>,
    tunnels: Mutex<HashMap<String, Tunnel>>,
    slots: Arc<Semaphore>,
    names: Mutex<HashMap<String, Connection>>,
}
impl App {
    // Called after the disabling transaction commits, while holding domain_changes.
    fn disconnect_user(&self, user_id: i64) {
        let connections: Vec<_> = self
            .names
            .lock()
            .unwrap()
            .iter()
            .filter(|(_, connection)| connection.user_id == Some(user_id))
            .map(|(host, connection)| (host.clone(), connection.cancel.clone()))
            .collect();
        for (host, cancel) in connections {
            cancel.cancel();
            self.tunnels.lock().unwrap().remove(&host);
        }
    }
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
        args.sse_max_streams as usize <= http_sse::MAX_STREAMS,
        "Invalid SSE limit"
    );
    ensure!(
        domain_valid(&args.domain)
            && (domain_valid(&args.connect_domain) || args.connect_domain == "localhost"),
        "Use lowercase DNS names, without scheme or wildcard"
    );
    let token = if let Some(path) = &args.token_file
        && args.enable_legacy_token
    {
        let token = std::fs::read_to_string(path)
            .context("Read legacy token file")?
            .trim()
            .as_bytes()
            .to_vec();
        ensure!(token.len() >= 32, "Token must contain at least 32 bytes");
        token
    } else {
        Vec::new()
    };
    let auth = auth::Auth::open(&args.database, args.allow_registration)?;
    if let Some(name) = args.bootstrap_admin {
        let password = if let Some(path) = args.bootstrap_password_file {
            std::fs::read_to_string(path)?
                .trim_end_matches(['\r', '\n'])
                .to_owned()
        } else {
            rpassword::prompt_password("Administrator password (12-128 bytes): ")?
        };
        auth.bootstrap(name, password).await?;
        eprintln!("Administrator created");
        if args.bootstrap_only {
            return Ok(());
        }
    }
    let app = Arc::new(App {
        http_sse: args.http_sse == "auto",
        sse_max_streams: args.sse_max_streams as usize,
        domain: args.domain,
        connect_domain: args.connect_domain,
        public_scheme: args.public_scheme,
        token,
        auth,
        trusted_proxies: args
            .trusted_proxies
            .iter()
            .filter(|s| !s.trim().is_empty())
            .map(|s| s.trim().parse())
            .collect::<std::result::Result<Vec<_>, _>>()
            .context("Invalid trusted proxy IP/CIDR")?,
        domain_changes: Arc::new(tokio::sync::Mutex::new(())),
        tunnels: Mutex::new(HashMap::new()),
        slots: Arc::new(Semaphore::new(128)),
        names: Mutex::new(HashMap::new()),
    });
    let listener = tokio::net::TcpListener::bind(&args.bind).await?;
    eprintln!("Listening on {}; TLS terminates at Traefik", args.bind);
    axum::serve(
        listener,
        Router::new()
            .fallback(handle)
            .with_state(app)
            .into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .with_graceful_shutdown(async {
        let _ = tokio::signal::ctrl_c().await;
    })
    .await?;
    Ok(())
}
async fn handle(
    State(app): State<Arc<App>>,
    axum::extract::ConnectInfo(peer): axum::extract::ConnectInfo<std::net::SocketAddr>,
    req: Request,
) -> Response {
    let meta = request_meta::RequestMeta::new(&req, peer, &app.trusted_proxies);
    let host = req
        .headers()
        .get("host")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .split(':')
        .next()
        .unwrap_or("");
    let audit = host.eq_ignore_ascii_case(&app.connect_domain)
        && (req.uri().path().starts_with("/_tunnel/api/") || req.uri().path() == "/_tunnel/ws");
    request_meta::CONTEXT
        .scope(meta, async {
            let response = route(app.clone(), req).await;
            if audit {
                app.auth.request_audit(response.status(), None).await;
            }
            response
        })
        .await
}
async fn route(app: Arc<App>, req: Request) -> Response {
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
            return app.auth.handle(&app, req).await;
        }
        let authorization = req
            .headers()
            .get("authorization")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        let legacy = !app.token.is_empty()
            && authorization
                .strip_prefix("Bearer ")
                .is_some_and(|value| bool::from(value.as_bytes().ct_eq(&app.token)));
        let identity = if legacy {
            None
        } else {
            match app.auth.tunnel_identity(authorization).await {
                Ok(identity) => Some(identity),
                Err(error) => return error.into_response(),
            }
        };
        let negotiate_sse = app.http_sse
            && req
                .headers()
                .get("x-tunnel-negotiation")
                .is_some_and(|v| v == "1");
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
        // Validate the upgrade before reserving a persistent account subdomain.
        let (mut parts, _) = req.into_parts();
        let ws = match WebSocketUpgrade::from_request_parts(&mut parts, &()).await {
            Ok(ws) => ws,
            Err(error) => return error.into_response(),
        };
        let changes = app.domain_changes.lock().await;
        let name = requested
            .cloned()
            .unwrap_or_else(|| Uuid::new_v4().simple().to_string()[..16].to_owned());
        let host = format!("{name}.{}", app.domain);
        if host.len() > 253 || host == app.connect_domain {
            return reply(StatusCode::BAD_REQUEST, "Reserved or invalid subdomain");
        }
        if app.names.lock().unwrap().contains_key(&host) {
            return reply(StatusCode::CONFLICT, "Subdomain is already in use");
        }
        if let Err(error) = app.auth.reserve(identity.as_ref(), &name).await {
            app.auth
                .event(identity.as_ref(), "tunnel_rejected", "failure", &name)
                .await;
            return error.into_response();
        }
        let cancel = CancellationToken::new();
        app.names.lock().unwrap().insert(
            host.clone(),
            Connection {
                user_id: identity.as_ref().map(|identity| identity.user_id),
                cancel: cancel.clone(),
            },
        );
        drop(changes);
        let lease = NameLease {
            app: app.clone(),
            host,
            cancel,
        };
        let meta = request_meta::current();
        return ws
            .max_message_size(MAX_WIRE)
            .max_frame_size(MAX_WIRE)
            .on_upgrade(move |socket| async move {
                let _permit = permit;
                request_meta::CONTEXT
                    .scope(meta, session(app, socket, lease, identity, negotiate_sse))
                    .await;
            });
    }
    if req.method() == http::Method::CONNECT {
        return reply(
            StatusCode::NOT_IMPLEMENTED,
            "CONNECT tunnels are not supported",
        );
    }
    let tunnel = app.tunnels.lock().unwrap().get(&host).cloned();
    let Some(tunnel) = tunnel else {
        return reply(StatusCode::NOT_FOUND, "Tunnel offline or unknown");
    };
    let cancel = tunnel.cancel.clone();
    tokio::select! {
        biased;
        _ = cancel.cancelled() => reply(StatusCode::BAD_GATEWAY, "Tunnel disconnected"),
        response = forward(tunnel, req) => response,
    }
}
async fn forward(tunnel: Tunnel, req: Request) -> Response {
    let Ok(permit) = tunnel.slots.clone().try_acquire_owned() else {
        return reply(StatusCode::SERVICE_UNAVAILABLE, "Tunnel busy");
    };
    if req.headers().contains_key("upgrade") {
        return websocket::upgrade(tunnel, req, permit).await;
    }
    let (parts, body) = req.into_parts();
    let candidate = tunnel.sse_enabled && parts.method == http::Method::GET;
    let accept = parts.headers.clone();
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
        headers: request_meta::current().forwarded_headers(&parts.headers),
        body: STANDARD.encode(data),
    };
    let (tx, rx) = oneshot::channel();
    tunnel.pending.lock().unwrap().insert(id.clone(), tx);
    // Always remove the pending sender if this HTTP task is cancelled/disconnected.
    let mut guard = sse::Guard {
        id: id.clone(),
        tunnel: tunnel.clone(),
        _permit: Some(permit),
        _sse_permit: None,
        cancel_peer: candidate,
    };
    if tunnel.tx.try_send(packet).is_err() {
        return reply(StatusCode::BAD_GATEWAY, "Tunnel disconnected or busy");
    }
    let response = match tokio::time::timeout(Duration::from_secs(35), rx).await {
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
        Ok(Ok(Packet::SseHead {
            status, headers, ..
        })) if candidate => {
            let restored = protocol::restore(headers.clone());
            if !http_sse::valid_head(&headers)
                || !restored
                    .as_ref()
                    .is_ok_and(|h| http_sse::selected(&accept, status, h))
            {
                return reply(StatusCode::BAD_GATEWAY, "Invalid SSE response");
            }
            let Ok(sse_permit) = tunnel.sse_slots.clone().try_acquire_owned() else {
                return reply(StatusCode::SERVICE_UNAVAILABLE, "SSE stream limit reached");
            };
            guard._sse_permit = Some(sse_permit);
            let body = sse::ResponseStream::new(guard);
            sse::control(&tunnel, Packet::SseWindow { id, ack_seq: 0 });
            let mut response = Response::new(Body::from_stream(body));
            *response.headers_mut() = restored.unwrap();
            return response;
        }
        Err(_) => return reply(StatusCode::GATEWAY_TIMEOUT, "Local service timed out"),
        _ => return reply(StatusCode::BAD_GATEWAY, "Tunnel disconnected"),
    };
    guard.cancel_peer = false;
    response
}
// The lease is dropped even if WebSocket upgrade fails, releasing the reservation.
struct NameLease {
    app: Arc<App>,
    host: String,
    cancel: CancellationToken,
}
impl Drop for NameLease {
    fn drop(&mut self) {
        self.cancel.cancel();
        self.app.tunnels.lock().unwrap().remove(&self.host);
        self.app.names.lock().unwrap().remove(&self.host);
    }
}
async fn session(
    app: Arc<App>,
    socket: WebSocket,
    lease: NameLease,
    identity: Option<auth::Identity>,
    negotiate_sse: bool,
) {
    let (tx, _data, control, mut outgoing) = wire::channel();
    let tunnel = Tunnel {
        tx,
        control: control.clone(),
        pending: Default::default(),
        slots: Arc::new(Semaphore::new(MAX_PENDING)),
        streams: Default::default(),
        cancel: lease.cancel.clone(),
        sse_enabled: negotiate_sse,
        sse_slots: Arc::new(Semaphore::new(app.sse_max_streams)),
        sse_streams: Default::default(),
        sse_heads: Default::default(),
    };
    let host = lease.host.clone();
    let cancel = lease.cancel.clone();
    let name = host
        .strip_suffix(&format!(".{}", app.domain))
        .unwrap_or(&host)
        .to_owned();
    let run = async {
        let (mut sink, mut stream) = socket.split();
        let url = format!("{}://{host}", app.public_scheme);
        if negotiate_sse {
            let offer = Packet::Ready {
                url: url.clone(),
                negotiation: Some(protocol::Negotiation::sse("offer")),
            };
            ensure!(
                send(
                    &mut sink,
                    Message::Text(serde_json::to_string(&offer)?.into())
                )
                .await,
                "Offer write failed"
            );
            let confirm = tokio::time::timeout(Duration::from_secs(10), stream.next()).await?;
            let Some(Ok(Message::Text(text))) = confirm else {
                anyhow::bail!("Missing capability confirmation")
            };
            ensure!(text.len() <= 4096, "Oversized negotiation");
            ensure!(
                matches!(serde_json::from_str::<Packet>(&text)?, Packet::Ready { url: u, negotiation: Some(n) } if u == url && n.is_sse("confirm")),
                "Invalid capability confirmation"
            );
        }
        // Publish after authentication/confirmation, before notifying readiness.
        // Requests can queue now, but the writer starts only after the final Ready.
        let changes = app.domain_changes.lock().await;
        ensure!(!cancel.is_cancelled(), "Tunnel revoked");
        app.tunnels
            .lock()
            .unwrap()
            .insert(host.clone(), tunnel.clone());
        drop(changes);
        let ready = Packet::Ready {
            url,
            negotiation: negotiate_sse.then(|| protocol::Negotiation::sse("active")),
        };
        ensure!(
            send(
                &mut sink,
                Message::Text(serde_json::to_string(&ready)?.into())
            )
            .await,
            "Ready write failed"
        );
        app.auth
            .event(identity.as_ref(), "tunnel_connect", "success", &name)
            .await;
        let mut writer = tokio::task::JoinSet::new();
        let pending = tunnel.pending.clone();
        writer.spawn(async move {
            while let Some(frame) = outgoing.next().await {
                let message = match frame {
                    wire::Out::Packet(packet) => {
                        if let Packet::Request { id, .. } = &packet
                            && !pending.lock().unwrap().contains_key(id)
                        {
                            continue;
                        }
                        Message::Text(serde_json::to_string(&packet)?.into())
                    }
                    wire::Out::Ping(data) => Message::Ping(data.into()),
                    wire::Out::Pong(data) => Message::Pong(data.into()),
                };
                ensure!(send(&mut sink, message).await, "Tunnel write failed");
            }
            Ok::<(), anyhow::Error>(())
        });
        let mut heartbeat = tokio::time::interval(Duration::from_secs(20));
        let mut last_seen = tokio::time::Instant::now();
        loop {
            tokio::select! {
                _ = writer.join_next() => anyhow::bail!("Tunnel writer stopped"),
                _ = heartbeat.tick() => {
                    ensure!(last_seen.elapsed() < Duration::from_secs(60), "Tunnel heartbeat timed out");
                    if let Some(identity) = &identity { ensure!(app.auth.active(identity).await, "Tunnel revoked"); }
                    control.try_send(wire::Out::Ping(Vec::new())).map_err(|_| anyhow::anyhow!("Control queue full"))?;
                }
                message = stream.next() => match message {
                    Some(Ok(Message::Text(text))) => {
                        last_seen = tokio::time::Instant::now();
                        let packet = serde_json::from_str::<Packet>(&text)?;
                        ensure!(http_sse::valid_packet(&packet, text.len()), "Invalid SSE envelope");
                        match packet {
                            packet @ (Packet::Response { .. } | Packet::WsOpened { .. }) => {
                                let id = match &packet { Packet::Response { id, .. } | Packet::WsOpened { id, .. } => id, _ => unreachable!() };
                                ensure!(!tunnel.sse_heads.lock().unwrap().contains(id), "Response after SSE head");
                                if let Some(tx) = tunnel.pending.lock().unwrap().remove(id) { let _ = tx.send(packet); }
                            }
                            Packet::WsFrame { id, frame } => websocket::dispatch(&tunnel.streams, &id, frame),
                            Packet::WsClose { id } => { tunnel.streams.lock().unwrap().remove(&id); tunnel.pending.lock().unwrap().remove(&id); }
                            packet @ Packet::SseHead { .. } if negotiate_sse => {
                                ensure!(text.len() <= http_sse::HEAD_WIRE, "Oversized SSE head");
                                let Packet::SseHead { ref id, .. } = packet else { unreachable!() };
                                ensure!(!tunnel.sse_heads.lock().unwrap().contains(id), "Duplicate SSE head");
                                if let Some(tx) = tunnel.pending.lock().unwrap().remove(id) {
                                    tunnel.sse_heads.lock().unwrap().insert(id.clone());
                                    let _ = tx.send(packet);
                                } else { sse::control(&tunnel, Packet::SseCancel { id: id.clone() }); }
                            }
                            packet @ (Packet::SseData { .. } | Packet::SseEnd { .. }) if negotiate_sse => {
                                ensure!(text.len() <= http_sse::DATA_WIRE, "Oversized SSE frame");
                                sse::dispatch(&tunnel, packet)?;
                            }
                            _ => anyhow::bail!("Unexpected tunnel packet"),
                        }
                    }
                    Some(Ok(Message::Pong(_))) => last_seen = tokio::time::Instant::now(),
                    Some(Ok(Message::Ping(data))) => { control.try_send(wire::Out::Pong(data.to_vec())).map_err(|_| anyhow::anyhow!("Control queue full"))?; }
                    _ => anyhow::bail!("Tunnel disconnected"),
                }
            }
        }
    };
    tokio::select! {
        biased;
        _ = cancel.cancelled() => {},
        result = run => { let result: Result<()> = result; if result.is_err() { eprintln!("tunnel end=connection_or_protocol_error"); } },
    }
    let outcome = if cancel.is_cancelled() {
        "revoked"
    } else {
        "closed"
    };
    cancel.cancel();
    app.tunnels.lock().unwrap().remove(&host);
    tunnel.pending.lock().unwrap().clear();
    tunnel.streams.lock().unwrap().clear();
    tunnel.sse_streams.lock().unwrap().clear();
    tunnel.sse_heads.lock().unwrap().clear();
    app.auth
        .event(identity.as_ref(), "tunnel_disconnect", outcome, &name)
        .await;
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
