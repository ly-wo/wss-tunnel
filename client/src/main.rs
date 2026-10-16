#[path = "../../protocol.rs"]
mod protocol;
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
#[command(about = "Expose a local HTTP port through your WSS tunnel server")]
struct Args {
    #[arg(value_parser=clap::value_parser!(u16).range(1..))]
    port: u16,
    #[arg(long, env = "TUNNEL_ENDPOINT")]
    server: String,
    #[arg(long)]
    name: Option<String>,
    #[arg(long, env = "TUNNEL_TOKEN_FILE")]
    token_file: std::path::PathBuf,
    #[arg(long, env = "TUNNEL_CA_CERT")]
    ca_cert: Option<std::path::PathBuf>,
}
#[tokio::main]
async fn main() -> Result<()> {
    dotenvy::dotenv().ok();
    let args = Args::parse();
    let mut url = reqwest::Url::parse(&args.server)?;
    ensure!(
        url.scheme() == "wss" && url.host_str().is_some(),
        "Server must use wss://"
    );
    ensure!(
        url.username().is_empty()
            && url.password().is_none()
            && url.query().is_none()
            && url.fragment().is_none(),
        "Use a WSS origin without credentials, query or fragment"
    );
    ensure!(
        url.path() == "/" || url.path() == "/_tunnel/ws",
        "Server must be an origin or /_tunnel/ws URL"
    );
    url.set_path("/_tunnel/ws");
    if let Some(name) = &args.name {
        ensure!(
            protocol::valid_name(name),
            "Name must be 1-63 lowercase letters/digits/hyphens, with no leading or trailing hyphen"
        );
        url.query_pairs_mut().append_pair("name", name);
    }
    let token = std::fs::read_to_string(&args.token_file).context("Read token file")?;
    ensure!(
        token.trim().len() >= 32,
        "Token must contain at least 32 bytes"
    );
    let mut request = url.as_str().into_client_request()?;
    request
        .headers_mut()
        .insert("authorization", format!("Bearer {}", token.trim()).parse()?);
    let mut roots = rustls::RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    if let Some(path) = args.ca_cert {
        let mut reader = std::io::BufReader::new(std::fs::File::open(path)?);
        for cert in rustls_pemfile::certs(&mut reader) {
            roots.add(cert?)?;
        }
    }
    let _ = rustls::crypto::ring::default_provider().install_default();
    let tls = rustls::ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth();
    let mut config = WebSocketConfig::default();
    config.max_message_size = Some(MAX_WIRE);
    config.max_frame_size = Some(MAX_WIRE);
    let (socket, _) = tokio::time::timeout(
        Duration::from_secs(15),
        connect_async_tls_with_config(
            request,
            Some(config),
            false,
            Some(Connector::Rustls(Arc::new(tls))),
        ),
    )
    .await
    .context("WSS connection timed out")??;
    let http = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .no_proxy()
        .timeout(Duration::from_secs(30))
        .build()?;
    let (mut sink, mut stream) = socket.split();
    let first = tokio::time::timeout(Duration::from_secs(10), stream.next()).await?;
    match first {
        Some(Ok(Message::Text(text))) => match serde_json::from_str::<Packet>(&text)? {
            Packet::Ready { url } => println!("{url} -> http://127.0.0.1:{}", args.port),
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
                            let http=http.clone(); let tx=tx.clone(); let port=args.port;
                            jobs.spawn(async move { let result=forward(http,port,packet).await; let _=tx.send(result).await; });
                        }
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
async fn forward(http: reqwest::Client, port: u16, packet: Packet) -> Packet {
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
        port: u16,
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
        let url = format!("http://127.0.0.1:{port}{path}");
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
        run(http, port, method, path, headers, body),
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
