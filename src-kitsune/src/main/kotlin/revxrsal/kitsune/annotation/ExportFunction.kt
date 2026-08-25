package revxrsal.kitsune.annotation

/**
 * Exposes a function to the Rust host.
 *
 * The function must be top-level or a member of an `object`, public, and not
 * `suspend` — the generated wrapper calls it from a static context with no
 * coroutine scope to call it in.
 *
 * [name] is the identifier the host dispatches with, and must be unique across
 * the module. Left blank, it is the function's own name.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ExportFunction(val name: String = "")
