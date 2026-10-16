use jni::JNIEnv;
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jlong};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};
use tokio::runtime::Runtime;
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;
use tunnel_client::{TunnelAuth, TunnelConfig, TunnelEvent, TunnelPhase, run_tunnel};

static RUNTIME: OnceLock<Mutex<Option<Runtime>>> = OnceLock::new();
static STATE: Mutex<Option<ActiveTunnel>> = Mutex::new(None);
static NEXT_GENERATION: AtomicU64 = AtomicU64::new(1);

struct ActiveTunnel {
    generation: u64,
    cancel: CancellationToken,
}

fn with_runtime<R>(run: impl FnOnce(&Runtime) -> R) -> Option<R> {
    let slot = RUNTIME.get_or_init(|| Mutex::new(None));
    let mut guard = slot.lock().ok()?;
    if guard.is_none() {
        *guard = Runtime::new().ok();
    }
    guard.as_ref().map(run)
}

fn clear_state_if_generation(generation: u64) {
    if let Ok(mut guard) = STATE.lock()
        && guard.as_ref().is_some_and(|a| a.generation == generation)
    {
        guard.take();
    }
}

#[derive(serde::Serialize)]
struct EventJson {
    generation: u64,
    phase: String,
    public_url: Option<String>,
    target_display: Option<String>,
    message: Option<String>,
}

fn phase_name(phase: &TunnelPhase) -> &'static str {
    match phase {
        TunnelPhase::Connecting => "connecting",
        TunnelPhase::Ready => "ready",
        TunnelPhase::Stopping => "stopping",
        TunnelPhase::Stopped => "stopped",
        TunnelPhase::Failed => "failed",
    }
}

fn emit_event(env: &mut JNIEnv, callback: &GlobalRef, event: &TunnelEvent) {
    let payload = EventJson {
        generation: event.generation,
        phase: phase_name(&event.phase).to_string(),
        public_url: event.public_url.clone(),
        target_display: event.target_display.clone(),
        message: event.message.clone(),
    };
    let json = match serde_json::to_string(&payload) {
        Ok(v) => v,
        Err(_) => return,
    };
    if let Ok(jstr) = env.new_string(json) {
        let _ = env.call_method(
            callback,
            "onEvent",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&jstr)],
        );
    }
}

fn fail_callback(env: &mut JNIEnv, callback: &GlobalRef, generation: u64, message: &str) {
    clear_state_if_generation(generation);
    emit_event(
        env,
        callback,
        &TunnelEvent {
            generation,
            phase: TunnelPhase::Failed,
            public_url: None,
            target_display: None,
            message: Some(message.to_string()),
        },
    );
}

fn stop_existing() {
    if let Ok(mut guard) = STATE.lock()
        && let Some(active) = guard.take()
    {
        active.cancel.cancel();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wlzxj_tunnel_jni_TunnelNative_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    server: JString,
    target: JString,
    name: JString,
    auth_kind: JString,
    auth_value: JString,
    username: JString,
    allow_insecure: jboolean,
    callback: JObject,
) -> jlong {
    let generation = NEXT_GENERATION.fetch_add(1, Ordering::SeqCst);
    let callback_ref = match env.new_global_ref(&callback) {
        Ok(v) => v,
        Err(_) => return -1,
    };
    let callback_pump = match env.new_global_ref(&callback) {
        Ok(v) => v,
        Err(_) => return -1,
    };

    let mut parse = || -> Result<(TunnelConfig, jni::JavaVM), String> {
        let server = env
            .get_string(&server)
            .map_err(|e| e.to_string())?
            .to_string_lossy()
            .into_owned();
        let target = env
            .get_string(&target)
            .map_err(|e| e.to_string())?
            .to_string_lossy()
            .into_owned();
        let name_opt = if env.is_same_object(&name, JObject::null()).unwrap_or(true) {
            None
        } else {
            let s = env
                .get_string(&name)
                .map_err(|e| e.to_string())?
                .to_string_lossy()
                .into_owned();
            if s.is_empty() { None } else { Some(s) }
        };
        let auth_kind = env
            .get_string(&auth_kind)
            .map_err(|e| e.to_string())?
            .to_string_lossy()
            .into_owned();
        let auth_value = env
            .get_string(&auth_value)
            .map_err(|e| e.to_string())?
            .to_string_lossy()
            .into_owned();
        let username = env
            .get_string(&username)
            .map_err(|e| e.to_string())?
            .to_string_lossy()
            .into_owned();

        let auth = match auth_kind.as_str() {
            "token" => TunnelAuth::Token(auth_value),
            "password" => TunnelAuth::Password {
                username,
                password: auth_value,
            },
            other => return Err(format!("Unknown auth kind: {other}")),
        };

        let config = TunnelConfig {
            server,
            target,
            name: name_opt,
            auth,
            ca_cert_path: None,
            local_ca_cert_path: None,
            allow_insecure_auth: allow_insecure != 0,
        };
        let vm = env.get_java_vm().map_err(|e| e.to_string())?;
        Ok((config, vm))
    };

    let parsed = match parse() {
        Ok(v) => v,
        Err(msg) => {
            fail_callback(&mut env, &callback_ref, generation, &msg);
            return -1;
        }
    };
    let (config, vm) = parsed;

    stop_existing();

    let cancel = CancellationToken::new();
    if let Ok(mut guard) = STATE.lock() {
        *guard = Some(ActiveTunnel {
            generation,
            cancel: cancel.clone(),
        });
    } else {
        fail_callback(
            &mut env,
            &callback_ref,
            generation,
            "JNI state lock poisoned",
        );
        return -1;
    }

    let (events_tx, mut events_rx) = mpsc::unbounded_channel();
    let cancel_worker = cancel.clone();
    let generation_worker = generation;

    let spawned = with_runtime(|rt| {
        rt.spawn(async move {
            let _ = run_tunnel(generation_worker, config, events_tx, cancel_worker).await;
        });
        let pump_generation = generation;
        rt.spawn(async move {
            while let Some(event) = events_rx.recv().await {
                if event.generation != pump_generation {
                    continue;
                }
                if let Ok(mut env) = vm.attach_current_thread() {
                    emit_event(&mut env, &callback_pump, &event);
                }
                if matches!(event.phase, TunnelPhase::Stopped | TunnelPhase::Failed) {
                    clear_state_if_generation(pump_generation);
                    break;
                }
            }
        });
    });

    if spawned.is_none() {
        fail_callback(
            &mut env,
            &callback_ref,
            generation,
            "Failed to start Tokio runtime",
        );
        return -1;
    }

    generation as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wlzxj_tunnel_jni_TunnelNative_nativeStop(
    _env: JNIEnv,
    _class: JClass,
    generation: jlong,
) {
    if let Ok(guard) = STATE.lock()
        && let Some(active) = guard.as_ref()
        && active.generation == generation as u64
    {
        active.cancel.cancel();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wlzxj_tunnel_jni_TunnelNative_nativeIsRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let running = STATE.lock().map(|g| g.is_some()).unwrap_or(false);
    if running { 1 } else { 0 }
}
