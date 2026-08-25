package revxrsal.kitsune.codegen.functions

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import revxrsal.kitsune.codegen.util.Annotations
import revxrsal.kitsune.codegen.util.ORDINAL_LIMIT
import revxrsal.kitsune.codegen.util.RESERVED_PACKAGE
import revxrsal.kitsune.codegen.util.assignOrdinals
import revxrsal.kitsune.codegen.util.isReservedPackageName
import revxrsal.kitsune.codegen.util.stringArgument
import revxrsal.kitsune.codegen.util.symbolsAnnotatedWith

/** Discovered by KSP through `META-INF/services`; must be public with a no-arg constructor. */
class FunctionProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        FunctionProcessor(environment.codeGenerator, environment.logger)
}

/**
 * Collects every `@ExportFunction` and writes the aggregated functions file.
 *
 * Runs exactly once. KSP calls a processor repeatedly until no symbols are
 * deferred, but nothing here defers: every rejection is decided from the
 * declaration alone, so a second round would find the same set and then fail on
 * the duplicate output path.
 */
class FunctionProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private var invoked = false

    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList()
        invoked = true

        val exported = LinkedHashMap<String, ExportedFun>()

        for (symbol in resolver.symbolsAnnotatedWith(Annotations.EXPORT_FUNCTION)) {
            // @ExportFunction is @Target(FUNCTION), so this is defensive — but a
            // malformed symbol arrives as a plain KSAnnotated, and a cast would
            // fail the build with a ClassCastException and no source location.
            if (symbol !is KSFunctionDeclaration) {
                logger.error("@ExportFunction is only applicable to functions.", symbol)
                continue
            }

            val declaredName = symbol.simpleName.asString()
            val exportedName = symbol.stringArgument(Annotations.EXPORT_FUNCTION, "name")
                ?: declaredName

            // The host dispatches by this name alone, so a clash is ambiguous no
            // matter which packages the two live in. Caught here rather than by
            // the generated map, which would silently keep the last one.
            exported[exportedName]?.let { clashing ->
                logger.error(
                    "Duplicate exported function name '$exportedName'; already used by " +
                        "${clashing.qualifiedName}.",
                    symbol,
                )
                return@let
            }

            if (!symbol.isPublic()) {
                logger.error("Exported functions must be public.", symbol)
                continue
            }
            if (symbol.isReservedPackageName()) {
                logger.error(
                    "Package '$RESERVED_PACKAGE' is reserved for generated code.",
                    symbol,
                )
                continue
            }

            val function = ExportedFun(
                function = symbol,
                containingJavaClass = resolver.getOwnerJvmClassName(symbol)!!,
            )
            if (!function.checkExportable(logger, "@ExportFunction")) continue

            exported[exportedName] = function
        }

        if (exported.isEmpty()) {
            // An app that exports nothing is legitimate, but this is also
            // exactly what a moved annotation package looks like — see
            // Annotations, which matches on names the compiler never checks.
            logger.warn(
                "Kitsune codegen found no @ExportFunction declarations. If that is unexpected, " +
                    "check that the annotations still live in ${Annotations.PACKAGE}."
            )
        }

        // The wire carries the ordinal as a u16. Past that the prefix wraps and
        // a call lands on whatever function shares the low sixteen bits.
        if (exported.size > ORDINAL_LIMIT) {
            logger.error(
                "Too many @ExportFunction declarations: ${exported.size} exceeds the $ORDINAL_LIMIT " +
                    "the wire ordinal can address."
            )
        }

        writeFunctionsFile(codeGenerator, exported.inOrdinalOrder())
        return emptyList()
    }
}

/**
 * Orders the exports by ordinal — index in the returned list *is* the ordinal.
 *
 * [assignOrdinals] is what decides the order, and it is deliberately blind to
 * the order this processor discovered them in: `TypeScriptProcessor` walks the
 * same annotations separately, and the two have to arrive at the same numbering
 * without being able to see each other.
 */
private fun Map<String, ExportedFun>.inOrdinalOrder(): List<ExportedEntry> =
    assignOrdinals(keys).map { ExportedEntry(it, getValue(it)) }
