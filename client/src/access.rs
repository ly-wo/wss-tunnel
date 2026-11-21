//! Public-domain access checks, independent of authentication to the tunnel server.
use crate::protocol::{self, Packet};
use anyhow::{Result, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

#[derive(Clone)]
pub struct AccessAuth {
    basic: Option<[u8; 32]>,
    bearer: Option<[u8; 32]>,
}
impl std::fmt::Debug for AccessAuth {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("AccessAuth([redacted])")
    }
}
fn digest(value: &[u8]) -> [u8; 32] {
    Sha256::digest(value).into()
}
impl AccessAuth {
    /// Both schemes may be enabled; either credential grants access.
    pub fn new(basic: Option<(String, String)>, bearer: Option<String>) -> Result<Self> {
        ensure!(
            basic.is_some() || bearer.is_some(),
            "Configure at least one access credential"
        );
        let basic = basic
            .map(|(username, password)| -> Result<_> {
                ensure!(
                    !username.is_empty()
                        && !username.contains(':')
                        && !username.chars().any(char::is_control),
                    "Invalid access Basic username"
                );
                ensure!(
                    !password.is_empty() && !password.chars().any(char::is_control),
                    "Invalid access Basic password"
                );
                Ok(digest(format!("{username}:{password}").as_bytes()))
            })
            .transpose()?;
        let bearer = bearer
            .map(|token| -> Result<_> {
                // RFC 6750 b64token: nonempty token chars followed by optional '=' padding.
                let value = token.trim_end_matches('=');
                ensure!(
                    !value.is_empty()
                        && value
                            .bytes()
                            .all(|c| c.is_ascii_alphanumeric() || b"-._~+/".contains(&c)),
                    "Invalid access Bearer token"
                );
                Ok(digest(token.as_bytes()))
            })
            .transpose()?;
        Ok(Self { basic, bearer })
    }
    fn accepts(&self, headers: &http::HeaderMap) -> bool {
        let mut values = headers.get_all(http::header::AUTHORIZATION).iter();
        let Some(value) = values.next().and_then(|v| v.to_str().ok()) else {
            return false;
        };
        if values.next().is_some() {
            return false;
        }
        let Some((scheme, credential)) = value.split_once(' ') else {
            return false;
        };
        let credential = credential.trim_start_matches(' ');
        if scheme.eq_ignore_ascii_case("Basic") {
            if let (Some(expected), Ok(decoded)) = (&self.basic, STANDARD.decode(credential)) {
                return bool::from(expected.ct_eq(&digest(&decoded)));
            }
        } else if scheme.eq_ignore_ascii_case("Bearer")
            && let Some(expected) = &self.bearer
        {
            return bool::from(expected.ct_eq(&digest(credential.as_bytes())));
        }
        false
    }
    /// Check before creating local requests/sockets. Never forward the gate credential.
    pub(crate) fn authorize_packet(&self, packet: &mut Packet) -> Option<Packet> {
        let (id, headers, websocket) = match packet {
            Packet::Request { id, headers, .. } => (id, headers, false),
            Packet::WsOpen { id, headers, .. } => (id, headers, true),
            _ => return None,
        };
        if let Ok(mut restored) = protocol::restore(headers.clone())
            && self.accepts(&restored)
        {
            restored.remove(http::header::AUTHORIZATION);
            *headers = protocol::headers(&restored);
            return None;
        }
        let mut response_headers = http::HeaderMap::new();
        response_headers.insert(http::header::CACHE_CONTROL, "no-store".parse().unwrap());
        if self.basic.is_some() {
            response_headers.append(
                http::header::WWW_AUTHENTICATE,
                "Basic realm=\"Tunnel access\", charset=\"UTF-8\""
                    .parse()
                    .unwrap(),
            );
        }
        if self.bearer.is_some() {
            response_headers.append(
                http::header::WWW_AUTHENTICATE,
                "Bearer realm=\"Tunnel access\"".parse().unwrap(),
            );
        }
        let headers = protocol::headers(&response_headers);
        Some(if websocket {
            Packet::WsOpened {
                id: id.clone(),
                status: 401,
                headers,
                protocol: None,
            }
        } else {
            Packet::Response {
                id: id.clone(),
                status: 401,
                headers,
                body: STANDARD.encode("Authentication required"),
            }
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn auth() -> AccessAuth {
        AccessAuth::new(
            Some(("visitor".into(), "pass:word".into())),
            Some("test-secret".into()),
        )
        .unwrap()
    }
    fn packet(values: &[&str], ws: bool) -> Packet {
        let mut h = http::HeaderMap::new();
        for value in values {
            h.append(http::header::AUTHORIZATION, value.parse().unwrap());
        }
        h.insert("x-app-header", "preserved".parse().unwrap());
        let headers = protocol::headers(&h);
        if ws {
            Packet::WsOpen {
                id: "1".into(),
                path: "/".into(),
                headers,
                protocols: vec![],
            }
        } else {
            Packet::Request {
                id: "1".into(),
                method: "GET".into(),
                path: "/".into(),
                headers,
                body: String::new(),
            }
        }
    }
    #[test]
    fn both_schemes_protect_http_and_websockets_and_strip_credentials() {
        for ws in [false, true] {
            for value in [
                format!("bAsIc {}", STANDARD.encode("visitor:pass:word")),
                "bearer test-secret".into(),
            ] {
                let mut p = packet(&[&value], ws);
                assert!(auth().authorize_packet(&mut p).is_none());
                let headers = match p {
                    Packet::Request { headers, .. } | Packet::WsOpen { headers, .. } => headers,
                    _ => unreachable!(),
                };
                let h = protocol::restore(headers).unwrap();
                assert!(!h.contains_key("authorization"));
                assert_eq!(h["x-app-header"], "preserved");
            }
            for values in [
                vec![],
                vec!["Bearer wrong"],
                vec!["Basic !!!"],
                vec!["Digest test-secret"],
                vec!["Bearer test-secret", "Bearer test-secret"],
            ] {
                let rejected = auth().authorize_packet(&mut packet(&values, ws)).unwrap();
                let (status, headers) = match rejected {
                    Packet::Response {
                        status, headers, ..
                    }
                    | Packet::WsOpened {
                        status, headers, ..
                    } => (status, headers),
                    _ => unreachable!(),
                };
                assert_eq!(status, 401);
                assert_eq!(
                    protocol::restore(headers)
                        .unwrap()
                        .get_all("www-authenticate")
                        .iter()
                        .count(),
                    2
                );
            }
        }
    }
    #[test]
    fn validates_configuration_and_redacts_secrets() {
        assert!(AccessAuth::new(None, None).is_err());
        assert!(AccessAuth::new(Some(("bad:name".into(), "pass".into())), None).is_err());
        assert!(AccessAuth::new(Some(("name".into(), "".into())), None).is_err());
        for token in ["", " ", "bad\ntoken", "foo=bar", "==="] {
            assert!(AccessAuth::new(None, Some(token.into())).is_err());
        }
        assert_eq!(format!("{:?}", auth()), "AccessAuth([redacted])");
        let only = AccessAuth::new(None, Some("test-secret".into())).unwrap();
        assert!(
            only.authorize_packet(&mut packet(
                &[&format!("Basic {}", STANDARD.encode("visitor:pass:word"))],
                false
            ))
            .is_some()
        );
    }
}
