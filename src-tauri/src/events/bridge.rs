use crate::events::EVENT_TX;
use crate::wire::ORDINAL_BYTES;
use jni::objects::{JClass, JPrimitiveArray};
use jni::sys::{jboolean, jbyte, jint};
use jni::{Env, bind_java_type, refs::LoaderContext};

bind_java_type! {
    pub Events => revxrsal.kitsune.app.Events,
    methods {
        static fn event_received(id: jint, payload: jbyte[]),
    },
    native_methods {
        static fn kotlin_emitted_event(id: jint, payload: jbyte[]) -> jboolean,
    },
}

impl EventsNativeInterface for EventsAPI {
    type Error = jni::errors::Error;

    /// Frames a Kotlin-side event and queues it for the frontend.
    ///
    /// Called on whatever thread raised the event, so it never blocks: a full
    /// queue answers `false` and Kotlin decides what that means, rather than the
    /// JVM stalling behind a webview that has stopped reading.
    fn kotlin_emitted_event<'local>(
        env: &mut Env<'local>,
        _class: JClass<'local>,
        ordinal: jint,
        payload: JPrimitiveArray<'local, jbyte>,
    ) -> Result<jboolean, Self::Error> {
        // Nothing has registered a pump yet — the window is still coming up, or
        // it went away. There is nowhere to put this.
        let Some(tx) = EVENT_TX.get() else {
            return Ok(false);
        };

        let len = payload.len(env)?;
        let mut frame = vec![0u8; ORDINAL_BYTES + len];
        frame[..ORDINAL_BYTES].copy_from_slice(&(ordinal as u16).to_le_bytes());

        // `jbyte` is `i8`: same size, same alignment, and every bit pattern is
        // valid for both, so this relabels the tail of the buffer rather than
        // converting it. The alternative — `convert_byte_array` into a `Vec<i8>`
        // and a cast per element — is a second allocation and a second pass to
        // produce the same bytes.
        let body = unsafe {
            std::slice::from_raw_parts_mut(
                frame[ORDINAL_BYTES..].as_mut_ptr().cast::<jbyte>(),
                len,
            )
        };
        payload.get_region(env, 0, body)?;

        Ok(tx.try_send(frame).is_ok())
    }
}

/// Loads `Events`, caches its class + method IDs and registers `kotlinEmittedEvent`.
pub fn init(env: &mut Env<'_>) -> Result<(), jni::errors::Error> {
    EventsAPI::get(env, &LoaderContext::default())?;
    Ok(())
}
