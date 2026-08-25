use crate::bridge::call_kotlin;

mod bridge;

#[tauri::command]
async fn call_kt(req: tauri::ipc::Request<'_>) -> Result<tauri::ipc::Response, String> {
    let name = req.headers().get("x-fn").and_then(|v| v.to_str().ok())
        .ok_or("missing x-fn header")?.to_string();
    let body = match req.body() {
        tauri::ipc::InvokeBody::Raw(v) => v.clone(),
        _ => return Err("expected raw body".into()),
    };
    let bytes = call_kotlin(&name, &body).await?;   // oneshot + CancelGuard
    Ok(tauri::ipc::Response::new(bytes))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub async fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![call_kt])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
