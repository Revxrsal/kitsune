package revxrsal.kitsune.codegen.util

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotated

/**
 * Fully-qualified names of the Kitsune annotations, and readers for their
 * arguments.
 *
 * These are strings rather than `KClass` references on purpose. Kono's codegen
 * module can do `implementation(project(":app"))` and then write
 * `getSymbolsWithAnnotation<ExportFunction>()`, because there the annotations
 * live in a library module separate from the one being processed. Here `:app`
 * *is* the module being processed — it applies this processor — so a dependency
 * back onto it would be a cycle. Referring to the annotations by name is what
 * breaks it.
 *
 * The cost is that a rename in `revxrsal.kitsune.annotation` goes unnoticed
 * until the processor silently finds nothing. [warnIfNothingFound] exists to
 * make that loud.
 */
object Annotations {

    const val PACKAGE = "revxrsal.kitsune.annotation"

    const val EXPORT_FUNCTION = "revxrsal.kitsune.functions.ExportFunction"
    const val EXPORT_EVENT = "revxrsal.kitsune.event.ExportEvent"
    const val LISTENER = "revxrsal.kitsune.event.Listener"
    const val ENTRYPOINT = "revxrsal.kitsune.app.KitsuneEntrypoint"
}

/** Package name reserved for generated code; user code may not live in it. */
const val RESERVED_PACKAGE = "revxrsal.kitsune.generated"

/** Returns all symbols annotated with [annotation], by fully-qualified name. */
fun Resolver.symbolsAnnotatedWith(annotation: String): Sequence<KSAnnotated> =
    getSymbolsWithAnnotation(annotation)

/**
 * Reads a `String` argument off an annotation.
 *
 * Returns `null` when the annotation is absent, when the argument is absent, or
 * when it was left at its default — KSP reports defaults as the declared default
 * value, so a blank result and an omitted argument are indistinguishable, and
 * both mean "derive it from the declaration name".
 */
fun KSAnnotated.stringArgument(annotation: String, argument: String): String? {
    val instance = annotations.firstOrNull {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == annotation
    } ?: return null
    val value = instance.arguments
        .firstOrNull { it.name?.asString() == argument }
        ?.value as? String
    return value?.takeIf { it.isNotBlank() }
}

/** Whether this symbol carries [annotation]. */
fun KSAnnotated.hasAnnotation(annotation: String): Boolean = annotations.any {
    it.annotationType.resolve().declaration.qualifiedName?.asString() == annotation
}
