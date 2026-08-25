use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jlong};
use jni::{Env, JavaVM, bind_java_type, refs::LoaderContext};
use parking_lot::Mutex;
use rustc_hash::FxHasher;
use std::collections::HashMap;
use std::hash::BuildHasherDefault;
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use tokio::sync::oneshot;

pub type KotlinResult = Result<Vec<u8>, String>;
static COUNTER: AtomicU64 = AtomicU64::new(0);
type FxMap = HashMap<u64, oneshot::Sender<KotlinResult>, BuildHasherDefault<FxHasher>>;
static PENDING: Mutex<FxMap> = Mutex::new(HashMap::with_hasher(BuildHasherDefault::new()));

bind_java_type! {
    pub Bridge => revxrsal.kitsune.functions.NativeFunctionBridge,
    methods {
        static fn submit(id: jlong, ordinal: jint, request: jbyte[]),
        static fn cancel(id: jlong),
    },
    native_methods {
        static fn rust_complete(id: jlong, data: jbyte[], error: JString),
    },
}

impl BridgeNativeInterface for BridgeAPI {
    type Error = jni::errors::Error;

    fn rust_complete<'local>(
        env: &mut Env<'local>,
        _class: JClass<'local>,
        id: jlong,
        data: JByteArray<'local>,
        error: JString<'local>,
    ) -> Result<(), Self::Error> {
        let result = if error.is_null() {
            Ok(env.convert_byte_array(data)?)
        } else {
            Err(error.try_to_string(env)?)
        };
        let tx = PENDING.lock().remove(&(id as u64));
        if let Some(tx) = tx {
            let _ = tx.send(result);
        }
        Ok(())
    }
}

/// Loads `Bridge`, caches its class + method IDs and registers `rustComplete`.
///
/// Idempotent; the cache lives in a `OnceLock` inside `BridgeAPI`.
pub fn init(env: &mut Env<'_>) -> Result<(), jni::errors::Error> {
    BridgeAPI::get(env, &LoaderContext::default())?;
    Ok(())
}

/// Cancels the Kotlin Job and drops the PENDING entry if we bail early.
struct Guard(u64, bool);

impl Drop for Guard {
    fn drop(&mut self) {
        if !self.1 {
            PENDING.lock().remove(&self.0);
            if let Ok(vm) = JavaVM::singleton() {
                let _ = vm.attach_current_thread(|env| -> Result<(), jni::errors::Error> {
                    Bridge::cancel(env, self.0 as jlong)
                });
            }
        }
    }
}

/// Hands `body` to the Kotlin export at `ordinal` and awaits its reply.
///
/// `ordinal` rather than a name: it is already an `i32` by the time it gets here,
/// so nothing is allocated on the way in — where a name meant a `NewStringUTF`
/// per call, and a hash lookup on the other side, to identify a function the
/// build had already numbered.
pub async fn call_kotlin(ordinal: jint, body: &[u8]) -> KotlinResult {
    let (tx, rx) = oneshot::channel::<KotlinResult>();
    let id = COUNTER.fetch_add(1, Relaxed);
    PENDING.lock().insert(id, tx);
    let mut guard = Guard(id, false);

    let vm = JavaVM::singleton().map_err(|e| e.to_string())?;
    vm.attach_current_thread(|env| -> Result<(), jni::errors::Error> {
        let payload = env.byte_array_from_slice(body)?;
        Bridge::submit(env, id as jlong, ordinal, &payload)
    })
    .map_err(|e| e.to_string())?;

    let out = match rx.await {
        Ok(res) => res,
        Err(e) => Err(format!("kotlin oneshot dropped: {e}")),
    };
    guard.1 = true;
    out
}
