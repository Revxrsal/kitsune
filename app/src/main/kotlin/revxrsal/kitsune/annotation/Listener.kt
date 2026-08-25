package revxrsal.kitsune.annotation

/**
 * Handles an event emitted by the Rust host.
 *
 * The function must take exactly one parameter — the event — and be public,
 * non-`suspend`, and either top-level or a member of an `object`.
 *
 * [event] names the event to listen for. Left blank, it is taken from the
 * parameter's type, which must then be annotated [ExportEvent].
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Listener(val event: String = "")
