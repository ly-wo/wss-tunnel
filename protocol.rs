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
        #[serde(default, skip_serializing_if = "Option::is_none")]
        negotiation: Option<Negotiation>,
    },
    SseHead {
        id: String,
        status: u16,
        headers: Vec<(String, String)>,
    },
    SseData {
        id: String,
        seq: u64,
        data: String,
    },
    SseEnd {
        id: String,
        last_seq: u64,
        reason: EndReason,
    },
    SseCancel {
        id: String,
    },
    SseWindow {
        id: String,
        ack_seq: u64,
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

#[derive(Serialize, Deserialize, Debug, PartialEq, Eq)]
pub struct Negotiation {
    pub phase: String,
    pub capabilities: Vec<String>,
}
impl Negotiation {
    pub fn sse(phase: &str) -> Self {
        Self {
            phase: phase.into(),
            capabilities: vec!["http_sse_v1".into()],
        }
    }
    pub fn is_sse(&self, phase: &str) -> bool {
        self.phase == phase
            && self.capabilities.len() <= 8
            && self.capabilities.iter().all(|c| c.len() <= 64)
            && self.capabilities.iter().any(|c| c == "http_sse_v1")
    }
}
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum EndReason {
    Eof,
    ReadError,
    IdleTimeout,
    BackpressureTimeout,
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ready_defaults_and_unknown_fields_preserve_legacy_json() {
        let packet: Packet =
            serde_json::from_str(r#"{"type":"ready","url":"https://example.test","future":true}"#)
                .unwrap();
        assert!(matches!(
            &packet,
            Packet::Ready {
                negotiation: None,
                ..
            }
        ));
        assert_eq!(
            serde_json::to_string(&packet).unwrap(),
            r#"{"type":"ready","url":"https://example.test"}"#
        );
        assert!(serde_json::from_str::<Packet>(r#"{"type":"future_packet"}"#).is_err());
        let mut offer = Negotiation::sse("offer");
        offer.capabilities.push("future_capability".into());
        assert!(offer.is_sse("offer"));
        assert!(!offer.is_sse("active"));
    }
}
