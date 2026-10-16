use anyhow::{Context, Result, ensure};
use clap::Parser;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;
use tunnel_client::{TunnelAuth, TunnelConfig, TunnelPhase, run_tunnel};

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
    let target = args.target.unwrap_or_else(|| {
        format!(
            "http://127.0.0.1:{}",
            args.port.expect("Clap requires port or target")
        )
    });

    let auth = if let Some(username) = &args.username {
        let password = if let Some(path) = &args.password_file {
            std::fs::read_to_string(path)
                .context("Read password file")?
                .trim_end_matches(['\r', '\n'])
                .to_owned()
        } else {
            rpassword::prompt_password("Password: ")?
        };
        TunnelAuth::Password {
            username: username.clone(),
            password,
        }
    } else {
        let path = args
            .token_file
            .as_ref()
            .context("Provide --username or --token-file")?;
        let token = std::fs::read_to_string(path).context("Read token file")?;
        TunnelAuth::Token(token)
    };

    let config = TunnelConfig {
        server: args.server,
        target,
        name: args.name,
        auth,
        ca_cert_path: args.ca_cert,
        local_ca_cert_path: args.local_ca_cert,
        allow_insecure_auth: args.allow_insecure_auth,
    };

    let cancel = CancellationToken::new();
    let cli_cancel = cancel.clone();
    tokio::spawn(async move {
        let _ = tokio::signal::ctrl_c().await;
        cli_cancel.cancel();
    });

    let (events_tx, mut events_rx) = mpsc::unbounded_channel();
    let generation = 1u64;
    let runner = tokio::spawn(run_tunnel(generation, config, events_tx, cancel.clone()));

    let mut printed = false;
    while let Some(event) = events_rx.recv().await {
        match event.phase {
            TunnelPhase::Ready => {
                if let (Some(url), Some(target)) = (&event.public_url, &event.target_display) {
                    println!("{url} -> {target}");
                    printed = true;
                }
            }
            TunnelPhase::Failed => {
                if let Some(msg) = event.message {
                    anyhow::bail!(msg);
                }
            }
            TunnelPhase::Stopped => break,
            _ => {}
        }
    }

    let result = tokio::time::timeout(Duration::from_secs(5), runner).await;
    match result {
        Ok(Ok(Ok(()))) => {
            ensure!(printed, "Server did not assign a public URL");
            Ok(())
        }
        Ok(Ok(Err(e))) => Err(e),
        Ok(Err(e)) => Err(e.into()),
        Err(_) => anyhow::bail!("Tunnel shutdown timed out"),
    }
}
