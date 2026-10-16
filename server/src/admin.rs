use super::*;
use serde::Serialize;

#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Settings {
    pub registration_enabled: bool,
    pub default_domain_limit: i64,
    pub audit_retention_days: i64,
    pub login_attempts_per_minute: usize,
    pub register_attempts_per_minute: usize,
}
pub(super) fn settings(db: &Connection) -> ApiResult<Settings> {
    let raw: String = db.query_row("SELECT value FROM settings WHERE id=1", [], |r| r.get(0))?;
    serde_json::from_str(&raw)
        .map_err(|_| ApiError(StatusCode::INTERNAL_SERVER_ERROR, "Invalid stored settings"))
}
pub(super) fn quota(db: &Connection, id: i64) -> ApiResult<i64> {
    let value: Option<i64> =
        db.query_row("SELECT domain_limit FROM users WHERE id=?1", [id], |r| {
            r.get(0)
        })?;
    Ok(value.unwrap_or(settings(db)?.default_domain_limit))
}
pub(super) fn is_admin(db: &Connection, id: i64) -> ApiResult<bool> {
    Ok(db
        .query_row(
            "SELECT is_admin=1 AND disabled=0 FROM users WHERE id=?1",
            [id],
            |r| r.get(0),
        )
        .optional()?
        .unwrap_or(false))
}
pub(super) fn migrate(db: &Connection, registration: bool) -> Result<()> {
    // A single migration transaction also serializes concurrent bootstrap/server starts.
    db.execute_batch("BEGIN IMMEDIATE")?;
    let result = (|| -> Result<()> {
        for (table, columns) in [
            (
                "users",
                vec![
                    ("is_admin", "INTEGER NOT NULL DEFAULT 0"),
                    ("disabled", "INTEGER NOT NULL DEFAULT 0"),
                    ("domain_limit", "INTEGER"),
                    ("registered_ip", "TEXT"),
                    ("last_login_ip", "TEXT"),
                    ("last_login_at", "INTEGER"),
                    ("last_seen_ip", "TEXT"),
                    ("last_seen_at", "INTEGER"),
                    ("last_user_agent", "TEXT"),
                    ("last_auth_method", "TEXT"),
                ],
            ),
            (
                "audit",
                vec![
                    ("client_ip", "TEXT"),
                    ("details", "TEXT NOT NULL DEFAULT '{}'"),
                    ("data", "TEXT NOT NULL DEFAULT '{}'"),
                    ("status", "INTEGER"),
                    ("duration_ms", "INTEGER"),
                ],
            ),
        ] {
            let existing = db
                .prepare(&format!("PRAGMA table_info({table})"))?
                .query_map([], |r| r.get::<_, String>(1))?
                .collect::<std::result::Result<Vec<_>, _>>()?;
            for (column, definition) in columns {
                if !existing.iter().any(|c| c == column) {
                    db.execute_batch(&format!(
                        "ALTER TABLE {table} ADD COLUMN {column} {definition}"
                    ))?;
                }
            }
        }
        db.execute_batch("CREATE TABLE IF NOT EXISTS settings(id INTEGER PRIMARY KEY CHECK(id=1),value TEXT NOT NULL); CREATE INDEX IF NOT EXISTS audit_ip ON audit(client_ip,id);")?;
        let initial = Settings {
            registration_enabled: registration,
            default_domain_limit: 10,
            audit_retention_days: 90,
            login_attempts_per_minute: 60,
            register_attempts_per_minute: 10,
        };
        db.execute(
            "INSERT OR IGNORE INTO settings(id,value) VALUES(1,?1)",
            [serde_json::to_string(&initial)?],
        )?;
        Ok(())
    })();
    if result.is_ok() {
        db.execute_batch("COMMIT")?;
    } else {
        db.execute_batch("ROLLBACK")?;
    }
    result
}
fn change(
    db: &Connection,
    identity: &Identity,
    action: &str,
    before: Value,
    after: Value,
    meta: &RequestMeta,
) -> ApiResult<()> {
    audit(
        db,
        Some(identity.user_id),
        &identity.username,
        action,
        "success",
        None,
        meta,
    )?;
    let id = db.last_insert_rowid();
    db.execute(
        "UPDATE audit SET data=?1 WHERE id=?2",
        params![json!({"before":before,"after":after}).to_string(), id],
    )?;
    Ok(())
}
impl Auth {
    pub(super) async fn settings(&self) -> ApiResult<Settings> {
        self.db(|db, _| settings(db)).await
    }
    pub async fn bootstrap(&self, name: String, password: String) -> Result<()> {
        let name = username(&name).map_err(|e| anyhow::anyhow!(e.1))?;
        anyhow::ensure!(password_valid(&password), "Password must be 12-128 bytes");
        let hash = hash_password(&password).map_err(|e| anyhow::anyhow!(e.1))?;
        self.db(move |db,meta| {
            let tx=db.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
            let count:i64=tx.query_row("SELECT COUNT(*) FROM users WHERE is_admin=1",[],|r|r.get(0))?;
            if count>0 {return Err(ApiError(StatusCode::CONFLICT,"An administrator already exists; bootstrap is disabled"));}
            let existing=tx.query_row("SELECT id,password_hash FROM users WHERE username=?1",[&name],|r|Ok((r.get::<_,i64>(0)?,r.get::<_,String>(1)?))).optional()?;
            let id=if let Some((id,stored))=existing {
                if !PasswordHash::new(&stored).is_ok_and(|h|Argon2::default().verify_password(password.as_bytes(),&h).is_ok()) {return Err(unauthorized());}
                tx.execute("UPDATE users SET is_admin=1,disabled=0 WHERE id=?1",[id])?;id
            } else {
                tx.execute("INSERT INTO users(username,password_hash,created_at,is_admin) VALUES(?1,?2,?3,1)",params![name,hash,now()])?;tx.last_insert_rowid()
            };
            audit(&tx,Some(id),&name,"admin_bootstrap","success",None,meta)?;
            tx.commit()?;Ok(())
        }).await.map_err(|e|anyhow::anyhow!(e.1))
    }
    pub async fn request_audit(&self, status: StatusCode, error: Option<String>) {
        let meta = request_meta::current();
        let actor = meta.actor.lock().unwrap().clone();
        let error = error.or_else(|| meta.failure.lock().unwrap().clone());
        let duration = meta
            .started
            .map(|t| t.elapsed().as_millis().min(i64::MAX as u128) as i64)
            .unwrap_or(0);
        if self
            .db(move |db, meta| {
                let tx = db.transaction()?;
                audit(
                    &tx,
                    actor.as_ref().map(|a| a.0),
                    actor.as_ref().map(|a| a.1.as_str()).unwrap_or("anonymous"),
                    "request",
                    if status.is_success() || status == StatusCode::SWITCHING_PROTOCOLS {
                        "success"
                    } else {
                        "failure"
                    },
                    None,
                    meta,
                )?;
                let id = tx.last_insert_rowid();
                tx.execute(
                    "UPDATE audit SET status=?1,duration_ms=?2,data=?3 WHERE id=?4",
                    params![
                        status.as_u16(),
                        duration,
                        json!({"reason":error}).to_string(),
                        id
                    ],
                )?;
                tx.commit()?;
                Ok(())
            })
            .await
            .is_err()
        {
            eprintln!("Could not record request audit event");
        }
    }
    pub(super) async fn admin_api(
        &self,
        app: &crate::App,
        req: Request,
        identity: Identity,
    ) -> ApiResult<Response> {
        let id = identity.user_id;
        if !self.db(move |db, _| is_admin(db, id)).await? {
            return Err(ApiError(
                StatusCode::FORBIDDEN,
                "Administrator access required",
            ));
        }
        let path = req.uri().path().to_owned();
        let method = req.method().clone();
        match (method,path.as_str()) {
            (Method::GET,"/_tunnel/api/admin/settings")=>Ok(Json(json!({"settings":self.settings().await?,"trusted_proxies":app.trusted_proxies.iter().map(ToString::to_string).collect::<Vec<_>>(),"domain":app.domain,"connect_domain":app.connect_domain})).into_response()),
            (Method::PUT,"/_tunnel/api/admin/settings")=>{
                let value:Settings=body(req).await?;
                if !(0..=1000).contains(&value.default_domain_limit)||!(1..=3650).contains(&value.audit_retention_days)||!(1..=10000).contains(&value.login_attempts_per_minute)||!(1..=10000).contains(&value.register_attempts_per_minute) { return Err(ApiError(StatusCode::BAD_REQUEST,"Settings outside allowed ranges")); }
                self.db(move |db,meta| {
                    let tx=db.transaction()?;
                    if !is_admin(&tx,id)? {return Err(unauthorized());}
                    let old=settings(&tx)?;
                    tx.execute("UPDATE settings SET value=?1 WHERE id=1",[serde_json::to_string(&value).unwrap()])?;
                    change(&tx,&identity,"settings_update",json!(old),json!(value),meta)?;
                    tx.commit()?;Ok(())
                }).await?;
                Ok(Json(json!({"settings":self.settings().await?})).into_response())
            },
            (Method::GET,"/_tunnel/api/admin/users")=>{
                let query=query(&req)?;
                let after=number(&query,"after",0)?;
                let search=query.get("username").cloned().unwrap_or_default();
                let rows=self.db(move |db,_| {
                    let default=settings(db)?.default_domain_limit;
                    let mut stmt=db.prepare("SELECT u.id,u.username,u.is_admin,u.disabled,u.domain_limit,u.created_at,u.registered_ip,u.last_login_ip,u.last_login_at,u.last_seen_ip,u.last_seen_at,u.last_user_agent,u.last_auth_method,u.token_prefix,(SELECT COUNT(*) FROM domains d WHERE d.user_id=u.id) FROM users u WHERE u.id>?1 AND (?2='' OR instr(u.username,?2)>0) ORDER BY u.id LIMIT 100")?;
                    let rows=stmt.query_map(params![after,search],|r|Ok(json!({"id":r.get::<_,i64>(0)?,"username":r.get::<_,String>(1)?,"is_admin":r.get::<_,bool>(2)?,"disabled":r.get::<_,bool>(3)?,"domain_limit_override":r.get::<_,Option<i64>>(4)?,"domain_limit":r.get::<_,Option<i64>>(4)?.unwrap_or(default),"created_at":r.get::<_,i64>(5)?,"registered_ip":r.get::<_,Option<String>>(6)?,"last_login_ip":r.get::<_,Option<String>>(7)?,"last_login_at":r.get::<_,Option<i64>>(8)?,"last_seen_ip":r.get::<_,Option<String>>(9)?,"last_seen_at":r.get::<_,Option<i64>>(10)?,"user_agent":r.get::<_,Option<String>>(11)?,"auth_method":r.get::<_,Option<String>>(12)?,"token_prefix":r.get::<_,Option<String>>(13)?,"domain_count":r.get::<_,i64>(14)?})))?;
                    Ok(rows.collect::<std::result::Result<Vec<_>,_>>()?)
                }).await?;
                let next=rows.last().and_then(|r|r["id"].as_i64());
                Ok(Json(json!({"users":rows,"next_cursor":next})).into_response())
            },
            (Method::GET,path) if path.starts_with("/_tunnel/api/admin/users/") && path.ends_with("/domains")=>{
                let user=path.trim_start_matches("/_tunnel/api/admin/users/").trim_end_matches("/domains").parse::<i64>().map_err(|_|ApiError(StatusCode::BAD_REQUEST,"Invalid user"))?;
                let names=self.db(move |db,_| {
                    let mut stmt=db.prepare("SELECT name,last_connected_at FROM domains WHERE user_id=?1 ORDER BY name")?;
                    Ok(stmt.query_map([user],|r|Ok((r.get::<_,String>(0)?,r.get::<_,i64>(1)?)))?.collect::<std::result::Result<Vec<_>,_>>()?)
                }).await?;
                let tunnels=app.tunnels.lock().unwrap();
                Ok(Json(json!({"domains":names.into_iter().map(|(name,last)|{let host=format!("{name}.{}",app.domain);json!({"name":name,"domain":host,"status":if tunnels.contains_key(&host){"online"}else{"offline"},"last_connected_at":last})}).collect::<Vec<_>>()})).into_response())
            },
            (Method::PUT,path) if path.starts_with("/_tunnel/api/admin/users/")=>{
                let user=path.trim_start_matches("/_tunnel/api/admin/users/").parse::<i64>().map_err(|_|ApiError(StatusCode::BAD_REQUEST,"Invalid user"))?;
                #[derive(Deserialize)] #[serde(deny_unknown_fields)] struct Update { disabled:bool, domain_limit_override:Option<i64> }
                let update:Update=body(req).await?;
                if update.domain_limit_override.is_some_and(|n|!(0..=1000).contains(&n)) {return Err(ApiError(StatusCode::BAD_REQUEST,"Quota must be 0-1000 or null"));}
                self.db(move |db,meta| {
                    let tx=db.transaction()?;
                    if !is_admin(&tx,id)? {return Err(unauthorized());}
                    let (name,admin,disabled,quota)=tx.query_row("SELECT username,is_admin,disabled,domain_limit FROM users WHERE id=?1",[user],|r|Ok((r.get::<_,String>(0)?,r.get::<_,bool>(1)?,r.get::<_,bool>(2)?,r.get::<_,Option<i64>>(3)?))).optional()?.ok_or(ApiError(StatusCode::NOT_FOUND,"User not found"))?;
                    if admin && update.disabled {return Err(ApiError(StatusCode::CONFLICT,"Cannot disable administrator"));}
                    tx.execute("UPDATE users SET disabled=?1,domain_limit=?2 WHERE id=?3",params![update.disabled,update.domain_limit_override,user])?;
                    if update.disabled {tx.execute("DELETE FROM sessions WHERE user_id=?1",[user])?;}
                    change(&tx,&identity,"user_update",json!({"user_id":user,"username":name,"disabled":disabled,"domain_limit_override":quota}),json!({"user_id":user,"username":name,"disabled":update.disabled,"domain_limit_override":update.domain_limit_override}),meta)?;
                    tx.commit()?;Ok(())
                }).await?;
                Ok(StatusCode::NO_CONTENT.into_response())
            },
            (Method::GET,"/_tunnel/api/admin/audit")=>{
                let q=query(&req)?;
                let before=number(&q,"before",i64::MAX)?;let since=number(&q,"since",0)?;let until=number(&q,"until",i64::MAX)?;
                let user=q.get("username").cloned().unwrap_or_default();let ip=q.get("ip").cloned().unwrap_or_default();let action=q.get("action").cloned().unwrap_or_default();let outcome=q.get("outcome").cloned().unwrap_or_default();
                let rows=self.db(move |db,_| {
                    let mut stmt=db.prepare("SELECT id,username,action,outcome,domain,created_at,details,data,status,duration_ms FROM audit WHERE id<?1 AND created_at>=?2 AND created_at<=?3 AND (?4='' OR username=?4) AND (?5='' OR client_ip=?5) AND (?6='' OR action=?6) AND (?7='' OR outcome=?7) ORDER BY id DESC LIMIT 100")?;
                    Ok(stmt.query_map(params![before,since,until,user,ip,action,outcome],|r|Ok(json!({"id":r.get::<_,i64>(0)?,"username":r.get::<_,String>(1)?,"action":r.get::<_,String>(2)?,"outcome":r.get::<_,String>(3)?,"domain":r.get::<_,Option<String>>(4)?,"created_at":r.get::<_,i64>(5)?,"request":serde_json::from_str::<Value>(&r.get::<_,String>(6)?).unwrap_or(json!({})),"data":serde_json::from_str::<Value>(&r.get::<_,String>(7)?).unwrap_or(json!({})),"status":r.get::<_,Option<i64>>(8)?,"duration_ms":r.get::<_,Option<i64>>(9)?})))?.collect::<std::result::Result<Vec<_>,_>>()?)
                }).await?;
                let next=rows.last().and_then(|r|r["id"].as_i64());
                Ok(Json(json!({"events":rows,"next_cursor":next})).into_response())
            },
            _=>Err(ApiError(StatusCode::NOT_FOUND,"Not found")),
        }
    }
}
fn query(req: &Request) -> ApiResult<std::collections::HashMap<String, String>> {
    axum::extract::Query::try_from_uri(req.uri())
        .map(|q| q.0)
        .map_err(|_| ApiError(StatusCode::BAD_REQUEST, "Invalid query"))
}
fn number(
    q: &std::collections::HashMap<String, String>,
    key: &str,
    default: i64,
) -> ApiResult<i64> {
    q.get(key)
        .map(|v| {
            v.parse::<i64>().ok().filter(|n| *n >= 0).ok_or(ApiError(
                StatusCode::BAD_REQUEST,
                "Invalid pagination or time",
            ))
        })
        .unwrap_or(Ok(default))
}
