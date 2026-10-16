use anyhow::Result;
use std::sync::Arc;

pub fn client_config(ca_cert_pem: Option<&[u8]>) -> Result<Arc<rustls::ClientConfig>> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let mut roots = rustls::RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    if let Some(pem) = ca_cert_pem {
        let mut reader = std::io::BufReader::new(pem);
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

pub fn client_config_from_path(
    path: Option<&std::path::Path>,
) -> Result<Arc<rustls::ClientConfig>> {
    match path {
        Some(path) => {
            let data = std::fs::read(path)?;
            client_config(Some(&data))
        }
        None => client_config(None),
    }
}
