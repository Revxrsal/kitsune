//! How each direction of the bridge names the export or event it is carrying.
//!
//! Both directions address by **ordinal** — an index into tables the build
//! generated for all three languages at once — but they carry it differently,
//! because the two transports offer different places to put it.
//!
//! **Inbound** (frontend → here) puts it in a request header. Tauri's IPC builds
//! a `Headers` map on every call regardless — `Content-Type`, `Tauri-Callback`,
//! `Tauri-Error`, `Tauri-Invoke-Key` — so one more entry rides along on
//! machinery already paid for, the payload passes through untouched, and reading
//! it back here allocates nothing.
//!
//! **Outbound** (here → frontend) has no header to use: events are pushed down a
//! `Channel` that carries only bytes. So that direction prefixes the frame with
//! a little-endian `u16`, which costs nothing extra because the buffer has to be
//! allocated to leave the JVM anyway.

use jni::sys::jint;

/// Bytes the ordinal takes at the head of an outbound event frame.
///
/// Mirrors `ORDINAL_BYTES` in `src/Bridge.ts`.
pub const ORDINAL_BYTES: usize = 2;

/// The header an inbound function call names its export in.
pub const FUNCTION_HEADER: &str = "x-fn";

/// The header an inbound event names itself in.
pub const EVENT_HEADER: &str = "x-event";

/// Reads the ordinal out of `header`.
///
/// Nothing is allocated on the way through: the header map was parsed as part of
/// the request, `to_str` only validates the bytes it already has, and the parse
/// reads digits straight out of them. The old spelling of this ended in
/// `.to_string()`, which was the one real cost the header approach ever had, and
/// it was ours rather than the transport's.
#[inline]
pub fn ordinal(req: &tauri::ipc::Request<'_>, header: &str) -> Result<jint, String> {
    req.headers()
        .get(header)
        .ok_or_else(|| format!("missing '{header}' header"))?
        .to_str()
        .ok()
        .and_then(|digits| digits.parse::<u16>().ok())
        .map(jint::from)
        .ok_or_else(|| format!("'{header}' is not an ordinal"))
}

/// The raw body of an inbound request, borrowed rather than copied.
#[inline]
pub fn body<'a>(req: &'a tauri::ipc::Request<'_>) -> Result<&'a [u8], String> {
    match req.body() {
        tauri::ipc::InvokeBody::Raw(bytes) => Ok(bytes),
        tauri::ipc::InvokeBody::Json(_) => Err("expected raw body".into()),
    }
}
