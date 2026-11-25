//! Shared SSE selection and fixed v1 wire bounds. Never parse event bodies.
use http::HeaderMap;
pub const FRAME: usize = 16 * 1024;
pub const WINDOW: u64 = 4;
pub const MAX_STREAMS: usize = 8;
pub const HEAD_WIRE: usize = 32 * 1024;
pub const DATA_WIRE: usize = 24 * 1024;
pub const HEAD_BYTES: usize = 16 * 1024;

// Split HTTP lists without splitting delimiters inside quoted parameter values.
fn split(value: &str, delimiter: char) -> Option<Vec<&str>> {
    let (mut quote, mut escape, mut start) = (false, false, 0);
    let mut out = Vec::new();
    for (i, c) in value.char_indices() {
        if escape {
            escape = false;
            continue;
        }
        match c {
            '\\' if quote => escape = true,
            '"' => quote = !quote,
            _ if c == delimiter && !quote => {
                out.push(value[start..i].trim());
                start = i + 1;
            }
            _ => {}
        }
    }
    if quote || escape {
        return None;
    }
    out.push(value[start..].trim());
    Some(out)
}
fn token(s: &str) -> bool {
    !s.is_empty()
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&b))
}
fn parameter(s: &str) -> Option<String> {
    if token(s) {
        return Some(s.into());
    }
    if !s.starts_with('"') || !s.ends_with('"') || s.len() < 2 {
        return None;
    }
    let mut out = String::new();
    let mut escape = false;
    for c in s[1..s.len() - 1].chars() {
        if escape {
            out.push(c);
            escape = false;
        } else if c == '\\' {
            escape = true;
        } else if c == '"' || c.is_control() {
            return None;
        } else {
            out.push(c);
        }
    }
    (!escape).then_some(out)
}
struct Media {
    kind: String,
    sub: String,
    params: Vec<(String, String)>,
    q: u16,
}
fn media(s: &str, accept: bool) -> Option<Media> {
    let parts = split(s, ';')?;
    let (kind, sub) = parts[0].split_once('/')?;
    if !token(kind) || !token(sub) || kind == "*" && sub != "*" {
        return None;
    }
    let mut m = Media {
        kind: kind.to_ascii_lowercase(),
        sub: sub.to_ascii_lowercase(),
        params: vec![],
        q: 1000,
    };
    let mut quality = false;
    for part in &parts[1..] {
        let (key, val) = part.split_once('=')?;
        let key = key.trim().to_ascii_lowercase();
        if !token(&key) {
            return None;
        }
        let raw = val.trim();
        if accept && key == "q" {
            if quality {
                return None;
            }
            quality = true;
            let (whole, frac) = raw.split_once('.').unwrap_or((raw, ""));
            if !matches!(whole, "0" | "1")
                || frac.len() > 3
                || !frac.bytes().all(|b| b.is_ascii_digit())
                || whole == "1" && frac.bytes().any(|b| b != b'0')
            {
                return None;
            }
            m.q = if whole == "1" {
                1000
            } else {
                format!("{frac:0<3}").parse().ok()?
            };
        } else {
            if quality || m.params.iter().any(|(k, _)| k == &key) {
                return None;
            }
            m.params.push((key, parameter(raw)?));
        }
    }
    Some(m)
}
pub fn selected(accept: &HeaderMap, status: u16, response: &HeaderMap) -> bool {
    if status != 200 || response.get_all("content-type").iter().count() != 1 {
        return false;
    }
    let Some(actual) = response
        .get("content-type")
        .and_then(|h| h.to_str().ok())
        .and_then(|h| media(h, false))
    else {
        return false;
    };
    if actual.kind != "text" || actual.sub != "event-stream" {
        return false;
    }
    if !accept.contains_key("accept") {
        return true;
    }
    let mut best: Option<((u8, usize), u16)> = None;
    for header in accept.get_all("accept") {
        let Some(items) = header.to_str().ok().and_then(|h| split(h, ',')) else {
            return false;
        };
        for item in items {
            let Some(range) = media(item, true) else {
                return false;
            };
            if range.kind != "*" && range.kind != actual.kind
                || range.sub != "*" && range.sub != actual.sub
            {
                continue;
            }
            if !range.params.iter().all(|(k, v)| {
                actual.params.iter().any(|(ak, av)| {
                    ak == k && (av == v || k == "charset" && av.eq_ignore_ascii_case(v))
                })
            }) {
                continue;
            }
            let rank = (
                if range.kind == "*" {
                    0
                } else if range.sub == "*" {
                    1
                } else {
                    2
                },
                range.params.len(),
            );
            match best {
                None => best = Some((rank, range.q)),
                Some((r, _)) if rank > r => best = Some((rank, range.q)),
                // Contradictory equally specific duplicates: conservatively exclude.
                Some((r, q)) if rank == r => best = Some((rank, q.min(range.q))),
                _ => {}
            }
        }
    }
    best.is_some_and(|(_, q)| q > 0)
}
pub fn valid_head(headers: &[(String, String)]) -> bool {
    if headers.len() > 128 {
        return false;
    }
    let Ok(map) = crate::protocol::restore(headers.to_vec()) else {
        return false;
    };
    map.iter()
        .map(|(k, v)| k.as_str().len() + v.len())
        .sum::<usize>()
        <= HEAD_BYTES
        && selected(&HeaderMap::new(), 200, &map)
}
// Validate before dispatching any extension packet. Legacy packet limits stay unchanged.
pub fn valid_packet(packet: &crate::protocol::Packet, wire_len: usize) -> bool {
    use crate::protocol::Packet;
    let (id, max) = match packet {
        Packet::SseHead { id, .. } => (id, HEAD_WIRE),
        Packet::SseData { id, .. } => (id, DATA_WIRE),
        Packet::SseEnd { id, .. } | Packet::SseCancel { id } | Packet::SseWindow { id, .. } => {
            (id, 1024)
        }
        _ => return true,
    };
    wire_len <= max
        && id.len() == 32
        && id
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn negotiation_media_ranges() {
        let mut response = HeaderMap::new();
        response.insert(
            "content-type",
            "Text/Event-Stream; charset=utf-8; profile=\"a,b\""
                .parse()
                .unwrap(),
        );
        for (value, yes) in [
            ("text/event-stream", true),
            ("*/*", true),
            ("text/*;q=0.1", true),
            ("text/event-stream;q=0, */*;q=1", false),
            (
                "application/json, text/event-stream; charset=UTF-8; q=0.5",
                true,
            ),
            ("text/event-stream; profile=\"a,b\"", true),
            ("text/event-stream; profile=other", false),
            ("text/event-stream;q=0.000", false),
            ("text/event-stream;q=1.001", false),
            ("text/event-stream;q=0.1234", false),
            ("text/event-stream;q=oops", false),
            ("text/event-stream;q=1, text/event-stream;q=0", false),
            ("text/event-stream;profile=\"unclosed", false),
        ] {
            let mut request = HeaderMap::new();
            request.insert("accept", value.parse().unwrap());
            assert_eq!(selected(&request, 200, &response), yes, "{value}");
        }
        assert!(selected(&HeaderMap::new(), 200, &response));
        assert!(!selected(&HeaderMap::new(), 401, &response));
    }
}
