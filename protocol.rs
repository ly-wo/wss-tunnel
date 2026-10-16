use http::HeaderMap;
use serde::{Deserialize, Serialize};
pub const MAX_BODY: usize = 5 * 1024 * 1024;
pub const MAX_WIRE: usize = 8 * 1024 * 1024;
pub const MAX_PENDING: usize = 32;
#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Packet {
    Ready {
        url: String,
    },
    Request {
        id: String,
        method: String,
        path: String,
        headers: Vec<(String, String)>,
        body: String,
    },
    WsOpen {
        id: String,
        path: String,
        headers: Vec<(String, String)>,
        protocols: Vec<String>,
    },
    WsOpened {
        id: String,
        status: u16,
        headers: Vec<(String, String)>,
        protocol: Option<String>,
    },
    WsFrame {
        id: String,
        frame: WireFrame,
    },
    WsClose {
        id: String,
    },
    Response {
        id: String,
        status: u16,
        headers: Vec<(String, String)>,
        body: String,
    },
}
#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "kind", rename_all = "snake_case")]
// Both text (UTF-8) and binary payloads use base64 to bound JSON expansion.
pub enum WireFrame {
    Text { data: String },
    Binary { data: String },
    Close { code: u16, reason: String },
}
// Each WebSocket leg handles Ping/Pong locally; text, binary and close travel
// across the multiplexed tunnel. Handshake fields are regenerated at each leg.
// Remove hop-by-hop headers, including fields named by Connection. Base64 keeps
// opaque HTTP header values intact, including repeated Set-Cookie headers.
pub fn headers(map: &HeaderMap) -> Vec<(String, String)> {
    use base64::{Engine, engine::general_purpose::STANDARD};
    let nominated: Vec<String> = map
        .get_all("connection")
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(','))
        .map(|v| v.trim().to_ascii_lowercase())
        .collect();
    map.iter()
        .filter(|(name, _)| {
            !matches!(
                name.as_str(),
                "connection"
                    | "keep-alive"
                    | "proxy-authenticate"
                    | "proxy-authorization"
                    | "te"
                    | "trailer"
                    | "transfer-encoding"
                    | "upgrade"
                    | "content-length"
                    | "host"
                    | "sec-websocket-key"
                    | "sec-websocket-version"
                    | "sec-websocket-accept"
                    | "sec-websocket-extensions"
                    | "sec-websocket-protocol"
            ) && !nominated.iter().any(|v| v == name.as_str())
        })
        .map(|(k, v)| (k.to_string(), STANDARD.encode(v.as_bytes())))
        .collect()
}
pub fn restore(items: Vec<(String, String)>) -> anyhow::Result<HeaderMap> {
    use base64::{Engine, engine::general_purpose::STANDARD};
    let mut map = HeaderMap::new();
    for (name, value) in items {
        map.append(
            http::header::HeaderName::from_bytes(name.as_bytes())?,
            http::HeaderValue::from_bytes(&STANDARD.decode(value)?)?,
        );
    }
    // Do not trust the peer to have filtered hop-by-hop headers.
    let filtered = headers(&map);
    map.clear();
    for (name, value) in filtered {
        map.append(
            http::header::HeaderName::from_bytes(name.as_bytes())?,
            http::HeaderValue::from_bytes(&STANDARD.decode(value)?)?,
        );
    }
    Ok(map)
}

pub fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 63
        && !name.starts_with('-')
        && !name.ends_with('-')
        && name
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}
