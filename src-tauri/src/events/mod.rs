pub mod bridge;

use crate::events::bridge::Events;
use crate::wire::{EVENT_HEADER, body, ordinal};
use jni::JavaVM;
use parking_lot::RwLock;
use std::sync::OnceLock;
use tauri::ipc::{Channel, InvokeResponseBody};
use tokio::sync::mpsc;

/// How many outbound event frames may be in flight before the JVM starts
/// dropping them.
///
/// There has to be a ceiling. The producer is a Kotlin thread that must not
/// block (see [`bridge::Events`]), so back-pressure is not available, and the
/// only two options past this point are dropping the frame or growing without
/// bound behind a webview that has stopped draining.
const OUTBOUND_QUEUE: usize = 1024;

/// The frontend's current event sink. Replaced, not added to, on every reload.
static CHANNEL: RwLock<Option<Channel<InvokeResponseBody>>> = RwLock::new(None);

/// The queue the JVM hands outbound frames to. Created with the pump, once.
pub(crate) static EVENT_TX: OnceLock<mpsc::Sender<Vec<u8>>> = OnceLock::new();

/// Registers the channel Kotlin-side events are pushed down.
///
/// The draining task is spawned once and reads whichever channel is current, so
/// a reload swaps the sink under it rather than tearing it down, which keeps
/// the queue, and anything already in it, alive across the gap.
#[tauri::command]
pub fn register_events_pump(pump: Channel<InvokeResponseBody>) {
    *CHANNEL.write() = Some(pump);

    EVENT_TX.get_or_init(|| {
        let (tx, mut rx) = mpsc::channel::<Vec<u8>>(OUTBOUND_QUEUE);
        tauri::async_runtime::spawn(async move {
            while let Some(frame) = rx.recv().await {
                // Cloned out of the lock rather than held across the send: the
                // send reaches into the webview, and the JVM thread registering
                // a new pump has no business waiting on that.
                let sink = CHANNEL.read().clone();
                if let Some(sink) = sink {
                    let _ = sink.send(InvokeResponseBody::Raw(frame));
                }
            }
        });
        tx
    });
}

/// Delivers an event raised by the frontend to every Kotlin `@Listener` for it.
#[tauri::command]
pub async fn dispatch_event(req: tauri::ipc::Request<'_>) -> Result<(), String> {
    let ordinal = ordinal(&req, EVENT_HEADER)?;
    let event = body(&req)?;

    let vm = JavaVM::singleton().map_err(|e| e.to_string())?;
    vm.attach_current_thread(|env| -> Result<(), jni::errors::Error> {
        let payload = env.byte_array_from_slice(event)?;
        Events::event_received(env, ordinal, &payload)
    })
    .map_err(|e| e.to_string())
}
