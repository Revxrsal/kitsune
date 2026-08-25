package revxrsal.kitsune.codegen.typescript

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import revxrsal.kitsune.codegen.functions.ExportedFun
import revxrsal.kitsune.codegen.functions.checkExportable
import revxrsal.kitsune.codegen.util.Annotations
import revxrsal.kitsune.codegen.util.isReservedPackageName
import revxrsal.kitsune.codegen.util.stringArgument
import revxrsal.kitsune.codegen.util.symbolsAnnotatedWith
import java.io.File

/** Discovered by KSP through `META-INF/services`; must be public with a no-arg constructor. */
class TypeScriptProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        TypeScriptProcessor(environment.options, environment.logger)
}

/** Absolute path of the file to write. Set from `kitsune.bindings` in the build. */
const val BINDINGS_OPTION = "kitsune.bindings"

/** Module specifier the generated file imports `Bridge` from. */
const val BRIDGE_IMPORT_OPTION = "kitsune.bridgeImport"

private const val DEFAULT_BRIDGE_IMPORT = "./Bridge"

/**
 * Writes the TypeScript bindings for every `@ExportFunction` and `@ExportEvent`.
 *
 * A third processor rather than an extra output on the two that already read
 * these annotations, because the frontend wants *one* file and a `CodeGenerator`
 * output can only be written by the processor that owns it. Two processors
 * cannot cooperate on one file — they cannot see each other's symbols, and
 * nothing orders them — so the choice is one file per processor or one processor
 * for the file. The scan is cheap; the split file would be the frontend's
 * problem forever.
 *
 * ## Diagnostics
 *
 * Malformed declarations are skipped in silence here. `FunctionProcessor` and
 * `EventProcessor` have already reported them against the same symbols and
 * failed the build; repeating each message under a second processor would double
 * every error in the log and point at the same line twice.
 *
 * ## Where the file goes
 *
 * Written straight to the configured path with ordinary file I/O, not through
 * KSP's `CodeGenerator`. The output is not an input to any Kotlin compilation —
 * it is source for a Vite build that lives outside this Gradle project — and
 * KSP's generator can only write inside `build/generated/ksp`, from where the
 * frontend has no way to import it.
 *
 * The cost is that KSP does not track the file: deleting it by hand does not
 * make `kspKotlin` out of date, and `./gradlew clean` is what brings it back.
 * The aggregating outputs of the other two processors are what keep the *content*
 * correct — they force every source file to be reprocessed whenever any of them
 * changes, so this processor never sees a partial set of exports.
 */
class TypeScriptProcessor(
    options: Map<String, String>,
    private val logger: KSPLogger,
) : SymbolProcessor {

    /** Blank means the build did not configure `kitsune.bindings`; nothing is generated. */
    private val output = options[BINDINGS_OPTION]?.takeIf { it.isNotBlank() }

    private val bridgeImport = options[BRIDGE_IMPORT_OPTION]?.takeIf { it.isNotBlank() }
        ?: DEFAULT_BRIDGE_IMPORT

    private var invoked = false

    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList()
        invoked = true

        val path = output ?: run {
            logger.info("Kitsune: no '$BINDINGS_OPTION' option; skipping TypeScript bindings.")
            return emptyList()
        }

        val functions = collectFunctions(resolver)
        val events = collectEvents(resolver)
        write(File(path), renderBindings(functions, events, bridgeImport, logger))
        return emptyList()
    }

    @OptIn(KspExperimental::class)
    private fun collectFunctions(resolver: Resolver): List<BoundFunction> {
        val bound = LinkedHashMap<String, BoundFunction>()

        for (symbol in resolver.symbolsAnnotatedWith(Annotations.EXPORT_FUNCTION)) {
            val declaration = symbol as? KSFunctionDeclaration ?: continue
            if (!declaration.isPublic() || declaration.isReservedPackageName()) continue

            // The JVM class only matters to the Kotlin wrapper, which resolves
            // the synthetic `$default` method by name; nothing in a TypeScript
            // signature depends on it.
            val function = ExportedFun(
                function = declaration,
                containingJavaClass = resolver.getOwnerJvmClassName(declaration).orEmpty(),
            )
            if (!function.checkExportable(SilentLogger, "@ExportFunction")) continue

            val name = declaration.stringArgument(Annotations.EXPORT_FUNCTION, "name")
                ?: function.name
            // First wins, matching the registry: a duplicate name is an error
            // FunctionProcessor has already raised.
            bound.putIfAbsent(name, BoundFunction(name, function))
        }
        return bound.values.toList()
    }

    private fun collectEvents(resolver: Resolver): List<BoundEvent> {
        val bound = LinkedHashMap<String, BoundEvent>()

        for (symbol in resolver.symbolsAnnotatedWith(Annotations.EXPORT_EVENT)) {
            val declaration = symbol as? KSClassDeclaration ?: continue
            if (declaration.isReservedPackageName()) continue
            if (declaration.typeParameters.isNotEmpty()) continue

            val id = declaration.stringArgument(Annotations.EXPORT_EVENT, "id")
                ?: declaration.simpleName.asString()
            bound.putIfAbsent(id, BoundEvent(id, declaration))
        }
        return bound.values.toList()
    }

    /**
     * Writes [contents] to [file], and only when they differ from what is there.
     *
     * The frontend dev server watches this file. Rewriting identical bytes on
     * every Gradle build would reload the app each time, so the comparison is
     * what keeps a Kotlin-only change from being visible in the browser.
     */
    private fun write(file: File, contents: String) {
        file.parentFile?.mkdirs()
        if (file.isFile && file.readText() == contents) return
        file.writeText(contents)
        logger.info("Kitsune: wrote TypeScript bindings to $file")
    }
}

/**
 * Swallows everything.
 *
 * Passed to the shared `checkExportable`, which reports as it validates. Here
 * only its verdict is wanted: the same check has already run under
 * `FunctionProcessor`, against the same declarations, with a logger that reaches
 * the build output.
 */
private object SilentLogger : KSPLogger {
    override fun logging(message: String, symbol: KSNode?) = Unit
    override fun info(message: String, symbol: KSNode?) = Unit
    override fun warn(message: String, symbol: KSNode?) = Unit
    override fun error(message: String, symbol: KSNode?) = Unit
    override fun exception(e: Throwable) = Unit
}
