#[path = "../../protocol.rs"]
mod protocol;
mod target;
mod websocket;
use anyhow::{Context, Result, bail, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use clap::Parser;
use futures_util::{SinkExt, StreamExt};
use protocol::{MAX_BODY, MAX_PENDING, MAX_WIRE, Packet};
use std::{sync::Arc, time::Duration};
use tokio::{sync::mpsc, task::JoinSet};
use tokio_tungstenite::{
    Connector, connect_async_tls_with_config,
    tungstenite::{Message, client::IntoClientRequest, protocol::WebSocketConfig},
};
#[derive(Parser)]
#[command(about = "Expose local HTTP(S) and WebSocket services through a WS(S) tunnel")]
struct Args {
    #[arg(value_parser=clap::value_parser!(u16).range(1..), required_unless_present="target", conflicts_with="target")]
    port: Option<u16>,
    #[arg(long, env = "TUNNEL_TARGET")]
    target: Option<String>,
    #[arg(long, env = "TUNNEL_LOCAL_CA_CERT")]
    local_ca_cert: Option<std::path::PathBuf>,
    #[arg(long, env = "TUNNEL_ENDPOINT")]
    server: String,
    #[arg(long)]
    name: Option<String>,
    #[arg(long, env = "TUNNEL_TOKEN_FILE")]
    token_file: Option<std::path::PathBuf>,
    #[arg(long, env = "TUNNEL_USERNAME")]
    username: Option<String>,
    #[arg(long, env = "TUNNEL_PASSWORD_FILE", requires = "username")]
    password_file: Option<std::path::PathBuf>,
    #[arg(
        long,
        help = "Allow password authentication over plaintext WS (local testing only)"
    )]
    allow_insecure_auth: bool,
    #[arg(long, env = "TUNNEL_CA_CERT")]
    ca_cert: Option<std::path::PathBuf>,
}
#[tokio::main]
async fn main() -> Result<()> {
    dotenvy::dotenv().ok();
    let args = Args::parse();
    let target = target::Target::new(&args.target.unwrap_or_else(|| {
        format!(
            "http://127.0.0.1:{}",
            args.port.expect("Clap requires port or target")
        )
    }))?;
    let mut url = target::server_url(&args.server)?;
    if let Some(name) = &args.name {
        ensure!(
            protocol::valid_name(name),
            "Name must be 1-63 lowercase letters/digits/hyphens, with no leading or trailing hyphen"
        );
        url.query_pairs_mut().append_pair("name", name);
    }
    let authorization = if let Some(username) = &args.username {
        ensure!(
            url.scheme() == "wss" || args.allow_insecure_auth,
            "Password connections require WSS; use --allow-insecure-auth only for local testing"
        );
        ensure!(
            !username.contains(':') && !username.is_empty(),
            "Invalid username"
        );
        let password = if let Some(path) = &args.password_file {
            std::fs::read_to_string(path)
                .context("Read password file")?
                .trim_end_matches(['\r', '\n'])
                .to_owned()
        } else {
            rpassword::prompt_password("Password: ")?
        };
        format!(
            "Basic {}",
            STANDARD.encode(format!("{username}:{password}"))
        )
    } else {
        let path = args
            .token_file
            .as_ref()
            .context("Provide --username or --token-file")?;
        let token = std::fs::read_to_string(path).context("Read token file")?;
        ensure!(
            token.trim().len() >= 32,
            "Token must contain at least 32 bytes"
        );
        format!("Bearer {}", token.trim())
    };
    let mut request = url.as_str().into_client_request()?;
    request
        .headers_mut()
        .insert("authorization", authorization.parse()?);
    let tls = tls_config(args.ca_cert.as_deref())?;
    let local_tls = tls_config(args.local_ca_cert.as_deref())?;
    let mut config = WebSocketConfig::default();
    config.max_message_size = Some(MAX_WIRE);
    config.max_frame_size = Some(MAX_WIRE);
    let (socket, _) = tokio::time::timeout(
        Duration::from_secs(15),
        connect_async_tls_with_config(request, Some(config), false, Some(Connector::Rustls(tls))),
    )
    .await
    .context("WebSocket tunnel connection timed out")??;
    let mut http = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .no_proxy()
        .timeout(Duration::from_secs(30));
    if let Some(path) = args.local_ca_cert.as_deref() {
        let mut reader = std::io::BufReader::new(std::fs::File::open(path)?);
        for cert in rustls_pemfile::certs(&mut reader) {
            http = http.add_root_certificate(reqwest::Certificate::from_der(cert?.as_ref())?);
        }
    }
    let http = http.build()?;
    let streams = websocket::Streams::default();
    let (mut sink, mut stream) = socket.split();
    let first = tokio::time::timeout(Duration::from_secs(10), stream.next()).await?;
    match first {
        Some(Ok(Message::Text(text))) => match serde_json::from_str::<Packet>(&text)? {
            Packet::Ready { url } => println!("{url} -> {}", target.display()),
            _ => bail!("Unexpected handshake"),
        },
        _ => bail!("Server did not assign a public URL"),
    }
    let (tx, mut rx) = mpsc::channel::<Packet>(MAX_PENDING);
    let mut jobs = JoinSet::new();
    let mut last_seen = tokio::time::Instant::now();
    let mut heartbeat = tokio::time::interval(Duration::from_secs(20));
    loop {
        tokio::select! {
            _=tokio::signal::ctrl_c()=> {break;}
            _=heartbeat.tick()=> {
                ensure!(last_seen.elapsed()<Duration::from_secs(60),"Server heartbeat timed out");
                tokio::time::timeout(Duration::from_secs(10),sink.send(Message::Ping(Vec::new().into()))).await??;
            }
            Some(_)=jobs.join_next(), if !jobs.is_empty()=> {}
            Some(packet)=rx.recv()=> {
                tokio::time::timeout(Duration::from_secs(10),sink.send(Message::Text(serde_json::to_string(&packet)?.into()))).await??;
            }
            message=stream.next()=>match message {
                Some(Ok(Message::Text(text)))=> {
                    last_seen=tokio::time::Instant::now();
                    let packet=serde_json::from_str::<Packet>(&text)?;
                    if let Packet::Request { ref id,.. }=packet {
                        if jobs.len()>=MAX_PENDING {
                            let response=failure(id.clone(),503,"Local concurrency limit reached");
                            tokio::time::timeout(Duration::from_secs(10),sink.send(Message::Text(serde_json::to_string(&response)?.into()))).await??;
                        } else {
                            let http=http.clone(); let tx=tx.clone(); let target=target.clone();
                            jobs.spawn(async move { let result=forward(http,target,packet).await; let _=tx.send(result).await; });
                        }
                    } else if let Packet::WsOpen {ref id,..}=packet {
                        if jobs.len()>=MAX_PENDING {
                            let response=Packet::WsOpened{id:id.clone(),status:503,headers:vec![],protocol:None};
                            tokio::time::timeout(Duration::from_secs(10),sink.send(Message::Text(serde_json::to_string(&response)?.into()))).await??;
                        } else {
                            let (in_tx,in_rx)=websocket::channel();
                            ensure!(!streams.lock().unwrap().contains_key(id),"Duplicate WebSocket stream id");
                            streams.lock().unwrap().insert(id.clone(),in_tx);
                            jobs.spawn(websocket::bridge(target.clone(),local_tls.clone(),packet,streams.clone(),tx.clone(),in_rx));
                        }
                    } else if let Packet::WsFrame{id,frame}=packet {
                        websocket::dispatch(&streams,&id,frame);
                    } else if let Packet::WsClose{id}=packet {
                        streams.lock().unwrap().remove(&id);
                    } else {bail!("Unexpected server packet");}
                }
                Some(Ok(Message::Ping(p)))=> { tokio::time::timeout(Duration::from_secs(10),sink.send(Message::Pong(p))).await??; }
                Some(Ok(Message::Pong(_)))=>last_seen=tokio::time::Instant::now(),
                Some(Ok(Message::Close(_)))|None=>bail!("Tunnel disconnected; run the command again to obtain a new URL"),
                Some(Err(e))=>return Err(e.into()),
                _=>bail!("Unsupported WebSocket message"),
            }
        }
    }
    jobs.abort_all();
    let _ = tokio::time::timeout(Duration::from_secs(2), sink.close()).await;
    Ok(())
}
fn failure(id: String, status: u16, message: &str) -> Packet {
    Packet::Response {
        id,
        status,
        headers: vec![],
        body: STANDARD.encode(message),
    }
}
async fn forward(http: reqwest::Client, target: target::Target, packet: Packet) -> Packet {
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
        target: target::Target,
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
            .headers(protocol::restore(headers)?)
            .body(data)
            .send()
            .await?;
        let status = response.status().as_u16();
        let headers = protocol::headers(response.headers());
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

fn tls_config(path: Option<&std::path::Path>) -> Result<Arc<rustls::ClientConfig>> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let mut roots = rustls::RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    if let Some(path) = path {
        let mut reader = std::io::BufReader::new(std::fs::File::open(path)?);
        for cert in rustls_pemfile::certs(&mut reader) {
            roots.add(cert?)?;
        }
    }
    Ok(Arc::new(
        rustls::ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth(),
    ))
}
