use jni::JavaVM;
use jni::objects::{JByteArray, JString};
use parking_lot::RwLock;
use tauri::ipc::{Channel, InvokeResponseBody};

static CHANNEL: RwLock<Option<Channel>> = RwLock::new(None);

#[tauri::command]
pub async fn register_events_pump(pump: Channel) {
    *CHANNEL.write() = Some(pump);
}

fn kotlin_emitted_event(id: JString, payload: JByteArray) -> anyhow::Result<()> {
    let env = JavaVM::singleton()?;
    let ch = CHANNEL.read().clone();
    if let Some(ch) = ch {
        env.attach_current_thread(|env| -> anyhow::Result<()> {
            let payload = env.convert_byte_array(payload)?;
            // should we make the id added at the beginning as a varstring?
            // like, its length prefixed, then the id, then the payload.
            ch.send(InvokeResponseBody::Raw(payload))?;
            Ok(())
        })?;
    }
    Ok(())
}
