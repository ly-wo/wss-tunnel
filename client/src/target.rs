use anyhow::{Result, ensure};
use reqwest::Url;

pub fn server_url(value: &str) -> Result<Url> {
    let mut url = Url::parse(value)?;
    let scheme = match url.scheme() {
        "ws" | "http" => "ws",
        "wss" | "https" => "wss",
        _ => anyhow::bail!("Server must use ws://, wss://, http:// or https://"),
    };
    validate(&url)?;
    ensure!(
        url.path() == "/" || url.path() == "/_tunnel/ws",
        "Server must be an origin or /_tunnel/ws URL"
    );
    url.set_scheme(scheme)
        .map_err(|_| anyhow::anyhow!("Invalid server scheme"))?;
    url.set_path("/_tunnel/ws");
    Ok(url)
}
fn validate(url: &Url) -> Result<()> {
    ensure!(
        url.host_str().is_some()
            && url.username().is_empty()
            && url.password().is_none()
            && url.query().is_none()
            && url.fragment().is_none(),
        "Use a URL without credentials, query or fragment"
    );
    Ok(())
}
#[derive(Clone)]
pub struct Target {
    base: Url,
}
impl Target {
    pub fn new(value: &str) -> Result<Self> {
        let mut base = Url::parse(value)?;
        let scheme = match base.scheme() {
            "http" | "ws" => "http",
            "https" | "wss" => "https",
            _ => anyhow::bail!("Target must use http(s):// or ws(s)://"),
        };
        validate(&base)?;
        base.set_scheme(scheme)
            .map_err(|_| anyhow::anyhow!("Invalid target scheme"))?;
        Ok(Self { base })
    }
    pub fn url(&self, path: &str, websocket: bool) -> Result<Url> {
        ensure!(
            path.starts_with('/')
                && !path.starts_with("//")
                && !path.contains('#')
                && !path.contains('\\'),
            "Invalid request target"
        );
        let base = self.base.as_str().trim_end_matches('/');
        let mut url = Url::parse(&format!("{base}{path}"))?;
        // A public path must never change the configured destination authority.
        ensure!(
            url.host_str() == self.base.host_str()
                && url.port_or_known_default() == self.base.port_or_known_default(),
            "Invalid destination"
        );
        if websocket {
            let scheme = if url.scheme() == "https" { "wss" } else { "ws" };
            url.set_scheme(scheme)
                .map_err(|_| anyhow::anyhow!("Invalid scheme"))?;
        }
        Ok(url)
    }
    pub fn display(&self) -> &str {
        self.base.as_str()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn server_aliases_and_ports() {
        for (input, expected) in [
            ("ws://example.com:80", "ws://example.com/_tunnel/ws"),
            (
                "http://example.com:8080",
                "ws://example.com:8080/_tunnel/ws",
            ),
            ("https://example.com", "wss://example.com/_tunnel/ws"),
            (
                "wss://example.com:8443",
                "wss://example.com:8443/_tunnel/ws",
            ),
        ] {
            assert_eq!(server_url(input).unwrap().as_str(), expected);
        }
        for invalid in [
            "ftp://example.com",
            "https://u:p@example.com",
            "wss://example.com/x",
            "wss://example.com?token=x",
        ] {
            assert!(server_url(invalid).is_err());
        }
    }
    #[test]
    fn target_schemes_and_paths() {
        let target = Target::new("wss://localhost:8443/base").unwrap();
        assert_eq!(
            target.url("/chat?q=a%2Fb", true).unwrap().as_str(),
            "wss://localhost:8443/base/chat?q=a%2Fb"
        );
        assert_eq!(
            target.url("/api", false).unwrap().as_str(),
            "https://localhost:8443/base/api"
        );
        assert!(target.url("//evil.example", true).is_err());
        assert!(target.url("/\\evil.example", true).is_err());
    }
}
