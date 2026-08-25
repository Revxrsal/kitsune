package revxrsal.kitsune.codegen.event

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ksp.toClassName
import revxrsal.kitsune.codegen.functions.ExportedFun
import revxrsal.kitsune.codegen.functions.checkExportable
import revxrsal.kitsune.codegen.util.Annotations
import revxrsal.kitsune.codegen.util.RESERVED_PACKAGE
import revxrsal.kitsune.codegen.util.hasAnnotation
import revxrsal.kitsune.codegen.util.isReservedPackageName
import revxrsal.kitsune.codegen.util.stringArgument
import revxrsal.kitsune.codegen.util.symbolsAnnotatedWith

/** Discovered by KSP through `META-INF/services`; must be public with a no-arg constructor. */
class EventProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        EventProcessor(environment.codeGenerator, environment.logger)
}

/**
 * Collects `@ExportEvent` classes and `@Listener` functions and writes the
 * aggregated events file.
 *
 * Events are gathered before listeners, not for tidiness: a listener without an
 * explicit `@Listener(event = ...)` takes its id from the event class it accepts,
 * so the class-to-id table has to be complete before the first listener is
 * resolved.
 */
class EventProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList()
        invoked = true

        val events = mutableListOf<ExportedEvent>()
        val idsByQualifiedName = mutableMapOf<String, String>()

        for (symbol in resolver.symbolsAnnotatedWith(Annotations.EXPORT_EVENT)) {
            if (symbol !is KSClassDeclaration) {
                logger.error("@ExportEvent is only applicable to classes.", symbol)
                continue
            }
            val event = parseEvent(symbol, idsByQualifiedName) ?: continue
            idsByQualifiedName[event.qualifiedName] = event.id
            events += event
        }

        val listeners = mutableListOf<RegisteredListener>()

        for (symbol in resolver.symbolsAnnotatedWith(Annotations.LISTENER)) {
            if (symbol !is KSFunctionDeclaration) {
                logger.error("@Listener is only applicable to functions.", symbol)
                continue
            }
            listeners += parseListener(symbol, resolver, idsByQualifiedName) ?: continue
        }

        writeEventsFile(codeGenerator, events, listeners)
        return emptyList()
    }

    private fun parseEvent(
        declaration: KSClassDeclaration,
        idsByQualifiedName: Map<String, String>,
    ): ExportedEvent? {
        if (declaration.classKind != ClassKind.CLASS && declaration.classKind != ClassKind.OBJECT) {
            logger.error(
                "@ExportEvent must be applied to a class or object, not " +
                    "${declaration.classKind.type}.",
                declaration,
            )
            return null
        }
        // An inner class needs an enclosing instance to construct, and nothing on
        // the host side can supply one. A nested `class` is fine — that is static
        // in Kotlin.
        if (Modifier.INNER in declaration.modifiers) {
            logger.error("@ExportEvent cannot be applied to an inner class.", declaration)
            return null
        }
        if (declaration.typeParameters.isNotEmpty()) {
            logger.error("@ExportEvent does not support generic classes.", declaration)
            return null
        }
        if (declaration.getVisibility() == Visibility.PRIVATE) {
            logger.error("@ExportEvent classes must be visible to generated code.", declaration)
            return null
        }
        if (declaration.isReservedPackageName()) {
            logger.error("Package '$RESERVED_PACKAGE' is reserved for generated code.", declaration)
            return null
        }
        // The generated registration hands the class to serializer<T>(), which
        // resolves at compile time — so a missing @Serializable surfaces as an
        // error inside generated code, where the source location is useless.
        if (!declaration.hasAnnotation(SERIALIZABLE_ANNOTATION)) {
            logger.error(
                "@ExportEvent classes must also be @kotlinx.serialization.Serializable; the host " +
                    "sends them as encoded payloads.",
                declaration,
            )
            return null
        }

        val className = declaration.toClassName()
        val qualifiedName = declaration.qualifiedName?.asString() ?: className.canonicalName
        val id = declaration.stringArgument(Annotations.EXPORT_EVENT, "id") ?: className.simpleName

        idsByQualifiedName.entries.firstOrNull { it.value == id }?.let { (owner, _) ->
            logger.error(
                "Duplicate event id '$id'; already used by $owner.",
                declaration,
            )
            return null
        }

        return ExportedEvent(
            className = className,
            qualifiedName = qualifiedName,
            id = id,
            declaration = declaration,
        )
    }

    @OptIn(KspExperimental::class)
    private fun parseListener(
        declaration: KSFunctionDeclaration,
        resolver: Resolver,
        idsByQualifiedName: Map<String, String>,
    ): RegisteredListener? {
        if (!declaration.isPublic()) {
            logger.error("@Listener functions must be public.", declaration)
            return null
        }
        if (declaration.isReservedPackageName()) {
            logger.error("Package '$RESERVED_PACKAGE' is reserved for generated code.", declaration)
            return null
        }

        val function = ExportedFun(
            function = declaration,
            containingJavaClass = resolver.getOwnerJvmClassName(declaration)!!,
        )
        if (!function.checkExportable(logger, "@Listener")) return null

        // Defaults would be unreachable anyway: the dispatcher always passes the
        // event, and there is no second argument for the host to omit.
        val parameter = function.parameters.singleOrNull() ?: run {
            logger.error(
                "A @Listener function must take exactly one parameter — the event — but takes " +
                    "${function.parameters.size}.",
                declaration,
            )
            return null
        }

        val eventDeclaration = parameter.type.declaration as? KSClassDeclaration ?: run {
            logger.error("The parameter of a @Listener function must be a class type.", declaration)
            return null
        }
        val eventQualifiedName = eventDeclaration.qualifiedName?.asString()

        val id = declaration.stringArgument(Annotations.LISTENER, "event")
            ?: idsByQualifiedName[eventQualifiedName]
            ?: run {
                logger.error(
                    "No event is registered for type $eventQualifiedName. Annotate it " +
                        "@ExportEvent, or name the event explicitly with @Listener(event = ...).",
                    declaration,
                )
                return null
            }

        return RegisteredListener(
            function = function,
            eventType = eventDeclaration.toClassName(),
            id = id,
        )
    }

    private companion object {
        const val SERIALIZABLE_ANNOTATION = "kotlinx.serialization.Serializable"
    }
}
