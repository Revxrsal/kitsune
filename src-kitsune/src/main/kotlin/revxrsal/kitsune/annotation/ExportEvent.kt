package revxrsal.kitsune.annotation

/**
 * Marks a class the Rust host can emit into the Kotlin side.
 *
 * The class must also be `@kotlinx.serialization.Serializable`: the host sends
 * it as an encoded payload, which the generated dispatcher decodes before
 * handing it to every matching [Listener].
 *
 * [id] is the identifier on the wire, and must be unique across the module.
 * Left blank, it is the class's simple name — the package is deliberately not
 * part of it, since the Rust side should not have to mirror Kotlin's package
 * structure.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ExportEvent(val id: String = "")
