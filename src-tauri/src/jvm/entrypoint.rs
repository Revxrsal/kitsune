// GENERATED FILE. DO NOT TOUCH
//
// Written by Kitsune's KSP processor from the `@KitsuneEntrypoint` declaration:
//
//     revxrsal.kitsune.TestApplication
//
// Move the annotation to change what this hands control to. Editing this file
// only means losing the edit on the next Gradle build.

use jni::{Env, jni_str, refs::LoaderContext};

/// Hands control to the Kotlin side.
///
/// This is what runs `KitsuneApplication`'s initializer, and that initializer is
/// what installs the singleton every later bridge call is dispatched through —
/// so this has to run, once, on an attached thread, before the first call can
/// arrive.
///
/// Nothing is returned. The application is reachable from the Kotlin side from
/// here on, and keeping a local reference to it here would only pin it to this
/// frame.
pub fn enter_app(env: &mut Env) -> anyhow::Result<()> {
    // An object is built by its own class initializer, so initializing the
    // class *is* the handover; there is no constructor for the host to call.
    LoaderContext::None.load_class(
        env,
        jni_str!("revxrsal.kitsune.TestApplication"),
        true,
    )?;
    Ok(())
}
