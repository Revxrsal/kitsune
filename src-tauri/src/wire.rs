//! The framing both directions of the bridge share.
//!
//! Every payload that crosses is `[ordinal][cbor]`, where the ordinal is a
//! little-endian `u16` naming which export or event the rest of the bytes are
//! for. It used to be a name in an `x-fn` / `x-event-id` header, which meant a
//! header map, a UTF-8 validation and a `String` allocation per call — and then
//! a hash lookup on the Kotlin side — to carry what is really an index into two
//! tables the build generated together.
//!
//! Two bytes rather than a varint: the split is then a constant offset the
//! compiler folds into the slice arithmetic, and 65 536 exports is far past the
//! point where anything else about this design matters. The codegen refuses to
//! emit more than fit.

use jni::sys::jint;

/// Bytes the ordinal takes at the head of every frame.
///
/// Mirrors `ORDINAL_BYTES` in `src/Bridge.ts`.
pub const ORDINAL_BYTES: usize = 2;

/// Splits a frame into its ordinal and the payload behind it.
///
/// The payload is borrowed, not copied: the only copy on the way to Kotlin is
/// the one JNI insists on when the `byte[]` is created, and handing it the tail
/// of the frame directly means Kotlin's decoder starts at index zero without
/// anyone having sliced anything.
#[inline]
pub fn split_ordinal(frame: &[u8]) -> Result<(jint, &[u8]), String> {
    match frame.split_at_checked(ORDINAL_BYTES) {
        Some((ordinal, payload)) => {
            Ok((u16::from_le_bytes([ordinal[0], ordinal[1]]) as jint, payload))
        }
        None => Err(format!(
            "frame is {} bytes, too short to carry its {ORDINAL_BYTES}-byte ordinal",
            frame.len(),
        )),
    }
}
