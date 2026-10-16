#[path = "admin.rs"]
mod admin;
use crate::request_meta::{self, RequestMeta};
use anyhow::Result;
use argon2::{Argon2, PasswordHash, PasswordHasher, PasswordVerifier, password_hash::SaltString};
use axum::{
    Json,
    body::to_bytes,
    extract::Request,
    response::{IntoResponse, Response},
};
use base64::{
    Engine,
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
};
use http::{Method, StatusCode};
use rand::{RngCore, rngs::OsRng};
use rusqlite::{Connection, OptionalExtension, params};
use serde::Deserialize;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    path::Path,
    sync::{Arc, Mutex},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::sync::Semaphore;

const SESSION_SECONDS: i64 = 12 * 60 * 60;
type ApiResult<T> = std::result::Result<T, ApiError>;
#[derive(Debug)]
pub struct ApiError(pub StatusCode, pub &'static str);
impl From<rusqlite::Error> for ApiError {
    fn from(e: rusqlite::Error) -> Self {
        eprintln!("Account database error: {e}");
        Self(
            StatusCode::INTERNAL_SERVER_ERROR,
            "Account storage unavailable",
        )
    }
}
impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        *request_meta::current().failure.lock().unwrap() = Some(self.1.to_owned());
        (self.0, Json(json!({"error":self.1}))).into_response()
    }
}
fn unauthorized() -> ApiError {
    ApiError(StatusCode::UNAUTHORIZED, "Invalid or expired credentials")
}
fn busy() -> ApiError {
    ApiError(
        StatusCode::TOO_MANY_REQUESTS,
        "Too many attempts; retry later",
    )
}
pub fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}
fn digest(token: &str) -> Vec<u8> {
    Sha256::digest(token.as_bytes()).to_vec()
}
fn secret(prefix: &str) -> String {
    let mut bytes = [0u8; 32];
    OsRng.fill_bytes(&mut bytes);
    format!("{prefix}{}", URL_SAFE_NO_PAD.encode(bytes))
}
fn username(input: &str) -> ApiResult<String> {
    let name = input.to_ascii_lowercase();
    if !(3..=32).contains(&name.len())
        || !name
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || c == b'_' || c == b'-')
    {
        return Err(ApiError(
            StatusCode::BAD_REQUEST,
            "Username must be 3-32 ASCII letters, digits, underscores or hyphens",
        ));
    }
    Ok(name)
}
fn password_valid(value: &str) -> bool {
    (12..=128).contains(&value.len())
}
fn hash_password(password: &str) -> ApiResult<String> {
    Argon2::default()
        .hash_password(password.as_bytes(), &SaltString::generate(&mut OsRng))
        .map(|h| h.to_string())
        .map_err(|_| ApiError(StatusCode::INTERNAL_SERVER_ERROR, "Password hashing failed"))
}
fn audit(
    db: &Connection,
    user: Option<i64>,
    name: &str,
    action: &str,
    outcome: &str,
    domain: Option<&str>,
    meta: &RequestMeta,
) -> ApiResult<()> {
    if let Some(id) = user {
        *meta.actor.lock().unwrap() = Some((id, name.to_owned()));
    }
    let details = serde_json::to_string(meta).unwrap_or_default();
    db.execute("INSERT INTO audit(user_id,username,action,outcome,domain,created_at,client_ip,details) VALUES (?1,?2,?3,?4,?5,?6,?7,?8)", params![user,name,action,outcome,domain,now(),meta.client_ip,details])?;
    db.execute(
        "DELETE FROM audit WHERE created_at < ?1",
        [now() - admin::settings(db)?.audit_retention_days * 86400],
    )?;
    if let Some(id) = user
        && outcome == "success"
        && !meta.client_ip.is_empty()
    {
        db.execute("UPDATE users SET last_seen_ip=?1,last_seen_at=?2,last_user_agent=?3,last_auth_method=?4 WHERE id=?5",params![meta.client_ip,now(),meta.user_agent,meta.auth_method,id])?;
        if action == "register" {
            db.execute(
                "UPDATE users SET registered_ip=?1 WHERE id=?2",
                params![meta.client_ip, id],
            )?;
        }
        if action == "login" {
            db.execute(
                "UPDATE users SET last_login_ip=?1,last_login_at=?2 WHERE id=?3",
                params![meta.client_ip, now(), id],
            )?;
        }
    }
    Ok(())
}
#[derive(Clone)]
pub struct Identity {
    pub user_id: i64,
    pub username: String,
    pub token_hash: Option<Vec<u8>>,
}
struct Window {
    started: Instant,
    login: usize,
    register: usize,
}
pub struct Auth {
    db: Arc<Mutex<Connection>>,
    workers: Arc<Semaphore>,
    hashing: Arc<Semaphore>,
    attempts: Mutex<Window>,
    dummy_hash: String,
}
impl Auth {
    pub fn open(path: &Path, registration: bool) -> Result<Self> {
        if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
            std::fs::create_dir_all(parent)?;
        }
        let db = Connection::open(path)?;
        db.busy_timeout(Duration::from_secs(3))?;
        db.execute_batch("PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL;
          CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY, username TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL, token_hash BLOB UNIQUE, token_prefix TEXT, token_created_at INTEGER, created_at INTEGER NOT NULL);
          CREATE TABLE IF NOT EXISTS sessions(hash BLOB PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id), expires_at INTEGER NOT NULL);
          CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);
          CREATE TABLE IF NOT EXISTS domains(name TEXT PRIMARY KEY, user_id INTEGER REFERENCES users(id), created_at INTEGER NOT NULL, last_connected_at INTEGER NOT NULL);
          CREATE INDEX IF NOT EXISTS domains_user ON domains(user_id);
          CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY, user_id INTEGER REFERENCES users(id), username TEXT NOT NULL, action TEXT NOT NULL, outcome TEXT NOT NULL, domain TEXT, created_at INTEGER NOT NULL);
          CREATE INDEX IF NOT EXISTS audit_user ON audit(user_id,id);
          CREATE INDEX IF NOT EXISTS audit_time ON audit(created_at);")?;
        admin::migrate(&db, registration)?;
        db.execute("DELETE FROM sessions WHERE expires_at<=?1", [now()])?;
        db.execute(
            "DELETE FROM audit WHERE created_at<?1",
            [now()
                - admin::settings(&db)
                    .map_err(|e| anyhow::anyhow!(e.1))?
                    .audit_retention_days
                    * 86400],
        )?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
            for suffix in ["-wal", "-shm"] {
                let mut sidecar = path.as_os_str().to_owned();
                sidecar.push(suffix);
                let sidecar = std::path::PathBuf::from(sidecar);
                if sidecar.exists() {
                    std::fs::set_permissions(sidecar, std::fs::Permissions::from_mode(0o600))?;
                }
            }
        }
        Ok(Self {
            db: Arc::new(Mutex::new(db)),
            workers: Arc::new(Semaphore::new(16)),
            hashing: Arc::new(Semaphore::new(2)),
            attempts: Mutex::new(Window {
                started: Instant::now(),
                login: 0,
                register: 0,
            }),
            dummy_hash: hash_password(&secret("dummy_")).map_err(|e| anyhow::anyhow!(e.1))?,
        })
    }
    async fn db<T: Send + 'static>(
        &self,
        f: impl FnOnce(&mut Connection, &RequestMeta) -> ApiResult<T> + Send + 'static,
    ) -> ApiResult<T> {
        let permit = self
            .workers
            .clone()
            .try_acquire_owned()
            .map_err(|_| busy())?;
        let db = self.db.clone();
        let meta = request_meta::current();
        tokio::task::spawn_blocking(move || {
            let _permit = permit;
            f(&mut db.lock().unwrap(), &meta)
        })
        .await
        .map_err(|_| ApiError(StatusCode::INTERNAL_SERVER_ERROR, "Account worker failed"))?
    }
    async fn attempt(&self, register: bool) -> ApiResult<()> {
        let settings = self.settings().await?;
        let mut window = self.attempts.lock().unwrap();
        if window.started.elapsed() >= Duration::from_secs(60) {
            *window = Window {
                started: Instant::now(),
                login: 0,
                register: 0,
            };
        }
        let count = if register {
            &mut window.register
        } else {
            &mut window.login
        };
        if *count
            >= if register {
                settings.register_attempts_per_minute
            } else {
                settings.login_attempts_per_minute
            }
        {
            return Err(busy());
        }
        *count += 1;
        Ok(())
    }
    async fn register(&self, input: Credentials) -> ApiResult<Value> {
        if !self.settings().await?.registration_enabled {
            return Err(ApiError(StatusCode::FORBIDDEN, "Registration is disabled"));
        }
        self.attempt(true).await?;
        let name = username(&input.username)?;
        if !password_valid(&input.password) {
            return Err(ApiError(
                StatusCode::BAD_REQUEST,
                "Password must be 12-128 bytes",
            ));
        }
        let permit = self
            .hashing
            .clone()
            .try_acquire_owned()
            .map_err(|_| busy())?;
        let hash = tokio::task::spawn_blocking(move || {
            let _permit = permit;
            hash_password(&input.password)
        })
        .await
        .map_err(|_| busy())??;
        self.db(move |db, meta| {
            let tx = db.transaction()?;
            if tx
                .query_row("SELECT id FROM users WHERE username=?1", [&name], |r| {
                    r.get::<_, i64>(0)
                })
                .optional()?
                .is_some()
            {
                audit(&tx, None, &name, "register", "conflict", None, meta)?;
                tx.commit()?;
                return Err(ApiError(StatusCode::CONFLICT, "Username already exists"));
            }
            tx.execute(
                "INSERT INTO users(username,password_hash,created_at) VALUES (?1,?2,?3)",
                params![name, hash, now()],
            )?;
            let id = tx.last_insert_rowid();
            audit(&tx, Some(id), &name, "register", "success", None, meta)?;
            tx.commit()?;
            Ok(json!({"username":name}))
        })
        .await
    }
    async fn password(&self, input: Credentials) -> ApiResult<Identity> {
        self.attempt(false).await?;
        let name = username(&input.username).map_err(|_| unauthorized())?;
        if input.password.len() > 128 {
            return Err(unauthorized());
        }
        let lookup = name.clone();
        let found = self
            .db(move |db, _meta| {
                Ok(db
                    .query_row(
                        "SELECT id,password_hash FROM users WHERE username=?1 AND disabled=0",
                        [lookup],
                        |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?)),
                    )
                    .optional()?)
            })
            .await?;
        let hash = found
            .as_ref()
            .map(|(_, h)| h.clone())
            .unwrap_or_else(|| self.dummy_hash.clone());
        let permit = self
            .hashing
            .clone()
            .try_acquire_owned()
            .map_err(|_| busy())?;
        let valid = tokio::task::spawn_blocking(move || {
            let _permit = permit;
            PasswordHash::new(&hash).is_ok_and(|h| {
                Argon2::default()
                    .verify_password(input.password.as_bytes(), &h)
                    .is_ok()
            })
        })
        .await
        .map_err(|_| busy())?
            && found.is_some();
        let id = found.map(|(id, _)| id);
        let logged = name.clone();
        self.db(move |db, meta| {
            audit(
                db,
                id,
                &logged,
                "login",
                if valid { "success" } else { "failure" },
                None,
                meta,
            )
        })
        .await?;
        if !valid {
            return Err(unauthorized());
        }
        Ok(Identity {
            user_id: id.unwrap(),
            username: name,
            token_hash: None,
        })
    }
    async fn login(&self, input: Credentials) -> ApiResult<Value> {
        let identity = self.password(input).await?;
        let session = secret("ws_session_");
        let hash = digest(&session);
        let expires = now() + SESSION_SECONDS;
        self.db(move |db, _meta| {
            let tx=db.transaction()?;
            tx.execute("DELETE FROM sessions WHERE expires_at<=?1",[now()])?;
            // Keep at most ten recent login sessions per account.
            tx.execute("DELETE FROM sessions WHERE hash IN (SELECT hash FROM sessions WHERE user_id=?1 ORDER BY expires_at DESC LIMIT -1 OFFSET 9)",[identity.user_id])?;
            tx.execute("INSERT INTO sessions(hash,user_id,expires_at) VALUES (?1,?2,?3)",params![hash,identity.user_id,expires])?;
            tx.commit()?;
            Ok(json!({"session":session,"expires_at":expires,"username":identity.username}))
        }).await
    }
    pub async fn tunnel_identity(&self, authorization: &str) -> ApiResult<Identity> {
        if let Some(encoded) = authorization.strip_prefix("Basic ") {
            if encoded.len() > 512 {
                return Err(unauthorized());
            }
            let decoded = STANDARD.decode(encoded).map_err(|_| unauthorized())?;
            let text = String::from_utf8(decoded).map_err(|_| unauthorized())?;
            let (name, password) = text.split_once(':').ok_or_else(unauthorized)?;
            return self
                .password(Credentials {
                    username: name.to_owned(),
                    password: password.to_owned(),
                })
                .await;
        }
        let token = authorization
            .strip_prefix("Bearer ")
            .filter(|t| t.starts_with("wt_") && t.len() == 46)
            .ok_or_else(unauthorized)?;
        let hash = digest(token);
        self.db(move |db, meta| {
            let identity = db
                .query_row(
                    "SELECT id,username FROM users WHERE token_hash=?1 AND disabled=0",
                    [&hash],
                    |r| {
                        Ok(Identity {
                            user_id: r.get(0)?,
                            username: r.get(1)?,
                            token_hash: Some(hash.clone()),
                        })
                    },
                )
                .optional()?
                .ok_or_else(unauthorized)?;
            audit(
                db,
                Some(identity.user_id),
                &identity.username,
                "token_auth",
                "success",
                None,
                meta,
            )?;
            Ok(identity)
        })
        .await
    }
    pub async fn active(&self, identity: &Identity) -> bool {
        let identity = identity.clone();
        self.db(move |db, _meta| {
            let matches = if let Some(hash) = identity.token_hash {
                db.query_row(
                    "SELECT EXISTS(SELECT 1 FROM users WHERE id=?1 AND disabled=0 AND token_hash=?2)",
                    params![identity.user_id, hash],
                    |r| r.get(0),
                )?
            } else {
                db.query_row(
                    "SELECT EXISTS(SELECT 1 FROM users WHERE id=?1 AND disabled=0)",
                    [identity.user_id],
                    |r| r.get(0),
                )?
            };
            Ok(matches)
        })
        .await
        .unwrap_or(false)
    }
    async fn session_identity(&self, token: &str) -> ApiResult<Identity> {
        if !token.starts_with("ws_session_") || token.len() != 54 {
            return Err(unauthorized());
        }
        let hash = digest(token);
        self.db(move |db, _meta| db.query_row("SELECT u.id,u.username FROM users u JOIN sessions s ON s.user_id=u.id WHERE s.hash=?1 AND s.expires_at>?2 AND u.disabled=0",params![hash,now()],|r|Ok(Identity{user_id:r.get(0)?,username:r.get(1)?,token_hash:None})).optional()?.ok_or_else(unauthorized)).await
    }
    pub async fn reserve(&self, identity: Option<&Identity>, name: &str) -> ApiResult<()> {
        let name = name.to_owned();
        let user = identity.map(|i| i.user_id);
        self.db(move |db, _meta| {
            let tx=db.transaction()?;
            if let Some(id)=user {
                let enabled:bool=tx.query_row("SELECT disabled=0 FROM users WHERE id=?1",[id],|r|r.get(0))?;
                if !enabled {return Err(unauthorized());}
            }
            let existing=tx.query_row("SELECT user_id FROM domains WHERE name=?1",[&name],|r|r.get::<_,Option<i64>>(0)).optional()?;
            match existing {
                Some(owner) if owner!=user => return Err(ApiError(StatusCode::CONFLICT,"Subdomain belongs to another account")),
                Some(_) => (),
                None => {
                    // Legacy shared-token names are only leased in memory, never permanently owned.
                    if user.is_none() { tx.commit()?; return Ok(()); }
                    if let Some(user)=user {
                        let count:i64=tx.query_row("SELECT COUNT(*) FROM domains WHERE user_id=?1",[user],|r|r.get(0))?;
                        if count>=admin::quota(&tx,user)? {return Err(ApiError(StatusCode::CONFLICT,"Account subdomain limit reached; reuse a name or delete an offline domain"));}
                    }
                    tx.execute("INSERT INTO domains(name,user_id,created_at,last_connected_at) VALUES (?1,?2,?3,0)",params![name,user,now()])?;
                }
            }
            tx.commit()?;Ok(())
        }).await
    }
    pub async fn event(
        &self,
        identity: Option<&Identity>,
        action: &str,
        outcome: &str,
        domain: &str,
    ) {
        let user = identity.map(|i| i.user_id);
        let name = identity
            .map(|i| i.username.clone())
            .unwrap_or_else(|| "legacy".to_owned());
        let action = action.to_owned();
        let outcome = outcome.to_owned();
        let domain = domain.to_owned();
        if self
            .db(move |db, meta| {
                let tx = db.transaction()?;
                if action == "tunnel_connect" {
                    tx.execute(
                        "UPDATE domains SET last_connected_at=?1 WHERE name=?2",
                        params![now(), domain],
                    )?;
                }
                audit(&tx, user, &name, &action, &outcome, Some(&domain), meta)?;
                tx.commit()?;
                Ok(())
            })
            .await
            .is_err()
        {
            eprintln!("Could not record tunnel audit event");
        }
    }
    pub async fn handle(&self, app: &crate::App, req: Request) -> Response {
        let response = self
            .api(app, req)
            .await
            .unwrap_or_else(IntoResponse::into_response);
        let mut response = response;
        for (key, value) in [
            ("cache-control", "no-store"),
            ("x-content-type-options", "nosniff"),
            ("referrer-policy", "no-referrer"),
            (
                "content-security-policy",
                "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
            ),
        ] {
            response.headers_mut().insert(key, value.parse().unwrap());
        }
        response
    }
    async fn api(&self, app: &crate::App, req: Request) -> ApiResult<Response> {
        let path = req.uri().path().to_owned();
        let method = req.method().clone();
        if method == Method::GET {
            let asset = match path.as_str() {
                "/" | "/_tunnel/account" => {
                    Some(("text/html; charset=utf-8", include_str!("account.html")))
                }
                "/_tunnel/admin" => Some(("text/html; charset=utf-8", include_str!("admin.html"))),
                "/_tunnel/admin.js" => {
                    Some(("text/javascript; charset=utf-8", include_str!("admin.js")))
                }
                "/_tunnel/account.js" => {
                    Some(("text/javascript; charset=utf-8", include_str!("account.js")))
                }
                "/_tunnel/account.css" => {
                    Some(("text/css; charset=utf-8", include_str!("account.css")))
                }
                _ => None,
            };
            if let Some((content_type, body)) = asset {
                return Ok(([("content-type", content_type)], body).into_response());
            }
            if path == "/_tunnel/api/config" {
                return Ok(Json(
                    json!({"registration":self.settings().await?.registration_enabled,"domain_limit":self.settings().await?.default_domain_limit}),
                )
                .into_response());
            }
        }
        if method == Method::POST
            && (path == "/_tunnel/api/register" || path == "/_tunnel/api/login")
        {
            let input = body::<Credentials>(req).await?;
            return if path.ends_with("/register") {
                Ok((StatusCode::CREATED, Json(self.register(input).await?)).into_response())
            } else {
                Ok(Json(self.login(input).await?).into_response())
            };
        }
        let session = req
            .headers()
            .get("authorization")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .unwrap_or("")
            .to_owned();
        let identity = self.session_identity(&session).await?;
        *request_meta::current().actor.lock().unwrap() =
            Some((identity.user_id, identity.username.clone()));
        if path.starts_with("/_tunnel/api/admin/") {
            return self.admin_api(app, req, identity).await;
        }
        match (method, path.as_str()) {
            (Method::POST, "/_tunnel/api/logout") => {
                let hash = digest(&session);
                self.db(move |db, meta| {
                    let tx = db.transaction()?;
                    tx.execute("DELETE FROM sessions WHERE hash=?1", [hash])?;
                    audit(
                        &tx,
                        Some(identity.user_id),
                        &identity.username,
                        "logout",
                        "success",
                        None,
                        meta,
                    )?;
                    tx.commit()?;
                    Ok(())
                })
                .await?;
                Ok(StatusCode::NO_CONTENT.into_response())
            }
            (Method::GET, "/_tunnel/api/me") => {
                let value=self.db(move |db, _meta| {
                    let token=db.query_row("SELECT token_prefix,token_created_at FROM users WHERE id=?1",[identity.user_id],|r|Ok((r.get::<_,Option<String>>(0)?,r.get::<_,Option<i64>>(1)?)))?;
                    Ok(json!({"username":identity.username,"token_prefix":token.0,"token_created_at":token.1,"domain_limit":admin::quota(db,identity.user_id)?,"is_admin":admin::is_admin(db,identity.user_id)?,"audit_retention_days":admin::settings(db)?.audit_retention_days}))
                }).await?;
                Ok(Json(value).into_response())
            }
            (Method::POST, "/_tunnel/api/token") => {
                // A fixed-size JSON body makes this mutation inaccessible to cross-origin HTML forms.
                let _: Value = body(req).await?;
                let token = secret("wt_");
                let hash = digest(&token);
                let prefix = token[..11].to_owned();
                let value=self.db(move |db, meta| {
                    let tx=db.transaction()?;
                    tx.execute("UPDATE users SET token_hash=?1,token_prefix=?2,token_created_at=?3 WHERE id=?4",params![hash,prefix,now(),identity.user_id])?;
                    audit(&tx,Some(identity.user_id),&identity.username,"token_rotate","success",None, meta)?;tx.commit()?;
                    Ok(json!({"token":token,"prefix":prefix}))
                }).await?;
                Ok(Json(value).into_response())
            }
            (Method::GET, "/_tunnel/api/domains") => {
                let user_id = identity.user_id;
                let limit = self.db(move |db, _| admin::quota(db, user_id)).await?;
                let domains=self.db(move |db, _meta| {
                    let mut stmt=db.prepare("SELECT name,created_at,last_connected_at FROM domains WHERE user_id=?1 ORDER BY created_at,name")?;
                    let rows=stmt.query_map([identity.user_id],|r|Ok((r.get::<_,String>(0)?,r.get::<_,i64>(1)?,r.get::<_,i64>(2)?)))?;
                    Ok(rows.collect::<std::result::Result<Vec<_>,_>>()?)
                }).await?;
                let tunnels = app.tunnels.lock().unwrap();
                let rows:Vec<_>=domains.into_iter().map(|(name,created,last)| {
                    let host=format!("{name}.{}",app.domain);
                    json!({"name":name,"domain":host,"status":if tunnels.contains_key(&host){"online"}else{"offline"},"created_at":created,"last_connected_at":last})
                }).collect();
                Ok(Json(json!({"domains":rows,"limit":limit})).into_response())
            }
            (Method::DELETE, path) if path.starts_with("/_tunnel/api/domains/") => {
                let name = path.trim_start_matches("/_tunnel/api/domains/").to_owned();
                if !crate::protocol::valid_name(&name) {
                    return Err(ApiError(StatusCode::BAD_REQUEST, "Invalid subdomain"));
                }
                // Serialize account domain reservations/deletions to avoid deleting a reconnecting lease.
                let _guard = app.domain_changes.lock().await;
                if app
                    .names
                    .lock()
                    .unwrap()
                    .contains(&format!("{name}.{}", app.domain))
                {
                    return Err(ApiError(
                        StatusCode::CONFLICT,
                        "Disconnect this domain before deleting it",
                    ));
                }
                self.db(move |db, meta| {
                    let tx = db.transaction()?;
                    if tx.execute(
                        "DELETE FROM domains WHERE name=?1 AND user_id=?2",
                        params![name, identity.user_id],
                    )? == 0
                    {
                        return Err(ApiError(StatusCode::NOT_FOUND, "Domain not found"));
                    }
                    audit(
                        &tx,
                        Some(identity.user_id),
                        &identity.username,
                        "domain_delete",
                        "success",
                        Some(&name),
                        meta,
                    )?;
                    tx.commit()?;
                    Ok(())
                })
                .await?;
                Ok(StatusCode::NO_CONTENT.into_response())
            }
            (Method::GET, "/_tunnel/api/audit") => {
                let rows=self.db(move |db, _meta| {
                    let mut stmt=db.prepare("SELECT id,action,outcome,domain,created_at FROM audit WHERE user_id=?1 ORDER BY id DESC LIMIT 100")?;
                    let rows=stmt.query_map([identity.user_id],|r|Ok(json!({"id":r.get::<_,i64>(0)?,"action":r.get::<_,String>(1)?,"outcome":r.get::<_,String>(2)?,"domain":r.get::<_,Option<String>>(3)?,"created_at":r.get::<_,i64>(4)?})))?;
                    Ok(rows.collect::<std::result::Result<Vec<_>,_>>()?)
                }).await?;
                Ok(Json(json!({"events":rows,"retention_days":self.settings().await?.audit_retention_days})).into_response())
            }
            _ => Err(ApiError(StatusCode::NOT_FOUND, "Not found")),
        }
    }
}
#[derive(Deserialize)]
struct Credentials {
    username: String,
    password: String,
}
async fn body<T: serde::de::DeserializeOwned>(req: Request) -> ApiResult<T> {
    if req
        .headers()
        .get("content-type")
        .and_then(|v| v.to_str().ok())
        .is_none_or(|v| v.split(';').next().unwrap_or("").trim() != "application/json")
    {
        return Err(ApiError(
            StatusCode::UNSUPPORTED_MEDIA_TYPE,
            "Use application/json",
        ));
    }
    let data = tokio::time::timeout(
        Duration::from_secs(10),
        to_bytes(req.into_body(), 16 * 1024),
    )
    .await
    .map_err(|_| ApiError(StatusCode::REQUEST_TIMEOUT, "Body read timed out"))?
    .map_err(|_| ApiError(StatusCode::PAYLOAD_TOO_LARGE, "Body exceeds 16 KiB"))?;
    serde_json::from_slice(&data)
        .map_err(|_| ApiError(StatusCode::BAD_REQUEST, "Invalid JSON body"))
}
