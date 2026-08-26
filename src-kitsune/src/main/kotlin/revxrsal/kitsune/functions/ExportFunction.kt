package revxrsal.kitsune.functions

/**
 * Exposes a function to the Rust host.
 *
 * The function must be top-level or a member of an `object`, and public. It may
 * be `suspend`: the generated wrapper launches it into a coroutine scope and
 * delivers the result back to the host when it completes, so suspending exports
 * never block the host thread. Plain functions run straight through with no
 * coroutine machinery at all.
 *
 * [name] must be unique across the module. Left blank, it is the function's own
 * name.
 *
 * The name is not what travels, though. Every export is also given an **ordinal**
 * at generation time — its index into the tables in `GeneratedFunctions`, and
 * into the matching bindings in the generated TypeScript — and that is what the
 * payload carries, in its first two bytes. Dispatch is then an array index
 * rather than a string decode and a hash. The name survives as what the
 * TypeScript binding is called, and as what a diagnostic can name.
 *
 * Ordinals are handed out by sorting the exported names, so adding an export
 * renumbers the ones after it alphabetically. Nothing may depend on a particular
 * number: the Kotlin registry and the TypeScript bindings are regenerated
 * together from this one source tree, and that is the only reason renumbering is
 * safe.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ExportFunction(val name: String = "")
