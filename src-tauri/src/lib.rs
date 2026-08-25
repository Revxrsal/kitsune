use crate::commands::call_kt;
use crate::events::{dispatch_event, register_events_pump};
use jni::{JavaVM};
use crate::jvm::entrypoint;

pub mod jvm;
pub mod commands;
pub mod events;
pub mod wire;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub async fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            call_kt,
            register_events_pump,
            dispatch_event
        ])
        .setup(|_| {
            let vm = JavaVM::singleton()?;
            vm.attach_current_thread(|env| -> anyhow::Result<()> {
                entrypoint::enter_app(env)?;
                Ok(())
            })?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
