use crate::commands::bridge::call_kotlin;
use crate::wire::{FUNCTION_HEADER, body, ordinal};

pub mod bridge;

/// Runs the Kotlin export the `x-fn` ordinal names and replies with its encoded
/// result.
///
/// The body is borrowed for the whole call rather than cloned. It is owned by
/// this future — `req` was moved in — so it outlives the `await` without the
/// payload being copied to prove it.
#[tauri::command]
pub async fn call_kt(req: tauri::ipc::Request<'_>) -> Result<tauri::ipc::Response, String> {
    let ordinal = ordinal(&req, FUNCTION_HEADER)?;
    let args = body(&req)?;
    Ok(tauri::ipc::Response::new(call_kotlin(ordinal, args).await?))
}
