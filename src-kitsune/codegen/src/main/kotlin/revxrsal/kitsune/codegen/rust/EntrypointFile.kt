package revxrsal.kitsune.codegen.rust

/**
 * The `@KitsuneEntrypoint` declaration, in the three spellings the generated
 * Rust needs.
 *
 * [internalName] is the JNI internal form — `revxrsal/kitsune/App`, what
 * `FindClass` and `new_object` take. [binaryName] is the same name in the dotted
 * form `Class.forName` expects, nested classes still joined by `$`. They differ
 * only in the separator, but each JNI entry point accepts exactly one of them
 * and silently fails to resolve the other, so both are computed once here rather
 * than converted at a call site.
 *
 * [isObject] decides *how* the handover happens; see [renderEntrypoint].
 */
class Entrypoint(
    val internalName: String,
    val binaryName: String,
    val isObject: Boolean,
)

/**
 * Renders `entrypoint.rs`: the one function the Rust host calls to hand control
 * to Kotlin.
 *
 * ## Two shapes, because Kotlin has two singletons
 *
 * What the host has to trigger is `KitsuneApplication`'s initializer — that is
 * what publishes the `application` instance every later bridge call is
 * dispatched through. Which JNI call triggers it depends on the declaration:
 *
 * - A **class** is instantiated. `new_object` with a `()V` descriptor runs the
 *   constructor, and the constructor runs the base class's `init` block. (`()V`
 *   and not `()L<class>;` — `new_object` rejects a non-void constructor
 *   descriptor at runtime, with `InvalidCtorReturn`.)
 * - An **object** is *already* the instance. Its `INSTANCE` field is assigned in
 *   the class initializer, so the object is constructed the moment the class is
 *   initialized, and there is nothing for the host to call. So the generated
 *   code loads the class with `initialize = true` and stops there — through
 *   `LoaderContext::load_class`, which reaches `Class.forName(name, true,
 *   loader)`, the one lookup that *specifies* initialization. `FindClass`
 *   initializes on HotSpot as well, but only as an implementation detail, and a
 *   handover is not a thing to leave to one.
 *
 * Note that `Env::load_class` is not the same call: it hardcodes
 * `initialize = false`, which loads the class and runs nothing. Against the
 * bundled runtime that is the difference between the app starting and the app
 * coming up with no `application` installed and no error anywhere.
 *
 * The dotted name goes with the load and the slashed one with the constructor
 * call, which is why [Entrypoint] carries both.
 */
fun renderEntrypoint(entrypoint: Entrypoint): String {
    val (imports, handover) = if (entrypoint.isObject) {
        "use jni::{Env, jni_str, refs::LoaderContext};" to """
            |    // An object is built by its own class initializer, so initializing the
            |    // class *is* the handover; there is no constructor for the host to call.
            |    LoaderContext::None.load_class(
            |        env,
            |        jni_str!("${entrypoint.binaryName}"),
            |        true,
            |    )?;
        """.trimMargin()
    } else {
        "use jni::{Env, jni_sig, jni_str};" to """
            |    env.new_object(
            |        jni_str!("${entrypoint.internalName}"),
            |        jni_sig!("()V"),
            |        &[],
            |    )?;
        """.trimMargin()
    }

    // The name is given a line of its own rather than wrapped into a sentence: a
    // long package would otherwise decide where the line breaks, and the file
    // would reflow every time the class moved.
    return """
// GENERATED FILE. DO NOT TOUCH
//
// Written by Kitsune's KSP processor from the `@KitsuneEntrypoint` declaration:
//
//     ${entrypoint.binaryName}
//
// Move the annotation to change what this hands control to. Editing this file
// only means losing the edit on the next Gradle build.

$imports

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
$handover
    Ok(())
}
""".trimStart()
}
