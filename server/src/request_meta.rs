use axum::extract::Request;
use ipnet::IpNet;
use serde::Serialize;
use std::{
    net::{IpAddr, SocketAddr},
    sync::{Arc, Mutex},
    time::Instant,
};

#[derive(Clone, Serialize)]
pub struct RequestMeta {
    pub request_id: String,
    pub client_ip: String,
    pub peer_ip: String,
    pub ip_source: String,
    pub method: String,
    pub path: String,
    pub user_agent: String,
    pub auth_method: String,
    #[serde(skip)]
    pub actor: Arc<Mutex<Option<(i64, String)>>>,
    #[serde(skip)]
    pub started: Option<Instant>,
    #[serde(skip)]
    pub failure: Arc<Mutex<Option<String>>>,
}
impl Default for RequestMeta {
    fn default() -> Self {
        Self {
            request_id: uuid::Uuid::new_v4().to_string(),
            client_ip: String::new(),
            peer_ip: String::new(),
            ip_source: "server".into(),
            method: String::new(),
            path: String::new(),
            user_agent: String::new(),
            auth_method: "server".into(),
            actor: Default::default(),
            started: None,
            failure: Default::default(),
        }
    }
}
tokio::task_local! { pub static CONTEXT: RequestMeta; }
pub fn current() -> RequestMeta {
    CONTEXT.try_with(Clone::clone).unwrap_or_default()
}
fn clean(value: &str, limit: usize) -> String {
    value
        .chars()
        .filter(|c| !c.is_control())
        .take(limit)
        .collect()
}
impl RequestMeta {
    pub fn new(req: &Request, peer: SocketAddr, trusted: &[IpNet]) -> Self {
        let peer_ip = peer.ip().to_canonical();
        let mut client_ip = peer_ip;
        let mut forwarded = false;
        if trusted.iter().any(|net| net.contains(&peer_ip)) {
            let values: Vec<_> = req.headers().get_all("x-forwarded-for").iter().collect();
            let chain = values
                .iter()
                .map(|v| v.to_str())
                .collect::<Result<Vec<_>, _>>()
                .ok()
                .and_then(|v| {
                    v.join(",")
                        .split(',')
                        .map(|s| s.trim().parse::<IpAddr>().map(|ip| ip.to_canonical()))
                        .collect::<Result<Vec<_>, _>>()
                        .ok()
                });
            if let Some(chain) = chain.filter(|v| !v.is_empty() && v.len() <= 16) {
                for ip in chain.iter().rev() {
                    if !trusted.iter().any(|net| net.contains(&client_ip)) {
                        break;
                    }
                    client_ip = *ip;
                    forwarded = true;
                }
            }
        }
        let authorization = req
            .headers()
            .get("authorization")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        let auth_method = if authorization.starts_with("Basic ") {
            "password"
        } else if authorization.starts_with("Bearer ws_session_") {
            "session"
        } else if authorization.starts_with("Bearer ") {
            "token"
        } else if req.uri().path().ends_with("/login") || req.uri().path().ends_with("/register") {
            "password"
        } else {
            "anonymous"
        };
        Self {
            client_ip: client_ip.to_string(),
            peer_ip: peer_ip.to_string(),
            ip_source: if forwarded { "trusted_proxy" } else { "direct" }.into(),
            method: req.method().to_string(),
            path: clean(req.uri().path(), 512),
            user_agent: clean(
                req.headers()
                    .get("user-agent")
                    .and_then(|v| v.to_str().ok())
                    .unwrap_or(""),
                512,
            ),
            auth_method: auth_method.into(),
            started: Some(Instant::now()),
            ..Default::default()
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn proxy_chain_requires_trust_and_rejects_malformed_headers() {
        let peer = "127.0.0.1:1234".parse().unwrap();
        let trusted = vec!["127.0.0.0/8".parse().unwrap()];
        for (value, expected) in [
            ("198.51.100.2", "198.51.100.2"),
            ("192.0.2.9, 198.51.100.2", "198.51.100.2"),
            ("garbage, 198.51.100.2", "127.0.0.1"),
        ] {
            let req = Request::builder()
                .header("x-forwarded-for", value)
                .body(axum::body::Body::empty())
                .unwrap();
            assert_eq!(RequestMeta::new(&req, peer, &trusted).client_ip, expected);
            assert_eq!(RequestMeta::new(&req, peer, &[]).client_ip, "127.0.0.1");
        }
    }
}
