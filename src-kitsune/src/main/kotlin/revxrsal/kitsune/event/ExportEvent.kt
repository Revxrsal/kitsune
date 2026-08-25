package revxrsal.kitsune.event

/**
 * Marks a class the Rust host can emit into the Kotlin side.
 *
 * The class must also be `@kotlinx.serialization.Serializable`: the host sends
 * it as an encoded payload, which the generated dispatcher decodes before
 * handing it to every matching [Listener].
 *
 * [id] must be unique across the module. Left blank, it is the class's simple
 * name — the package is deliberately not part of it, since the Rust side should
 * not have to mirror Kotlin's package structure.
 *
 * The id is not what travels, though. Every exported event is also given an
 * **ordinal** at generation time — its index into the tables in
 * `GeneratedEvents`, and into the matching tables in the generated TypeScript —
 * and that is what the payload carries, in its first two bytes. Dispatch is then
 * an array index rather than a string decode and a hash. The id survives as the
 * name a diagnostic can use, and as the thing `@Listener(event = ...)` refers to.
 *
 * Ordinals are handed out by sorting the ids, so adding an event renumbers the
 * ones after it alphabetically. Nothing may depend on a particular number: the
 * Kotlin registry and the TypeScript bindings are regenerated together from this
 * one source tree, and that is the only reason renumbering is safe.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ExportEvent(val id: String = "")
