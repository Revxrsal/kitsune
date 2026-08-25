package revxrsal.kitsune.codegen.functions

import com.google.devtools.ksp.processing.CodeGenerator
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.ksp.writeTo
import revxrsal.kitsune.codegen.util.RESERVED_PACKAGE
import revxrsal.kitsune.codegen.util.Runtime

/** File name (without extension) of the aggregated functions file. */
private const val FILE_NAME = "functions"

/**
 * Writes `revxrsal.kitsune.generated.functions.kt`: one wrapper per
 * `@ExportFunction`, the `@Serializable` argument holders they decode into, and
 * the `GeneratedFunctions` object mapping exported names onto them.
 *
 * One file rather than one per function, and one write rather than one per
 * round. A `CodeGenerator` refuses to create the same output path twice, so the
 * aggregate can only be emitted once the full set is known — which is also why
 * the processor collects everything before calling this.
 *
 * `aggregating = true` tells KSP's incremental machinery that this output
 * depends on the whole set of annotated sources, so adding an export elsewhere
 * invalidates it. Marking it isolating instead would leave the map stale.
 */
fun writeFunctionsFile(
    codeGenerator: CodeGenerator,
    exported: Map<String, ExportedFun>,
) {
    val file = FileSpec.builder(RESERVED_PACKAGE, FILE_NAME)

    for (function in exported.values) {
        function.addTo(file)
    }

    file.addType(generatedFunctionsObject(exported))
    file.build().writeTo(codeGenerator = codeGenerator, aggregating = true)
}

/**
 * The registry the host dispatches through.
 *
 * Keyed by exported name — the value from `@ExportFunction(name = ...)`, falling
 * back to the declaration's own name — which is what travels over the bridge.
 * The wrapper it points at is named after the *declaration*, so the two can
 * differ.
 */
private fun generatedFunctionsObject(exported: Map<String, ExportedFun>): TypeSpec {
    val mapType = MAP.parameterizedBy(STRING, Runtime.ExportedFunction)

    val initializer = buildCodeBlock {
        if (exported.isEmpty()) {
            add("emptyMap()")
            return@buildCodeBlock
        }
        add("mapOf(\n⇥")
        for ((exportedName, function) in exported) {
            // Which of the two wrappers this is has to be recorded here: the
            // host cannot call a suspending export the way it calls a plain one,
            // and erasing the difference into one function type would leave
            // FunctionHandler unable to tell them apart.
            val shape =
                if (function.isSuspend) Runtime.SuspendingFunction else Runtime.BlockingFunction
            add("%S to %T(::%L),\n", exportedName, shape, function.name)
        }
        add("⇤)")
    }

    return TypeSpec.objectBuilder("GeneratedFunctions")
        .addKdoc(
            "Every function annotated `@ExportFunction`, keyed by the name the host\n" +
                "dispatches with. Generated — do not edit.\n"
        )
        .addProperty(
            PropertySpec.builder("functions", mapType)
                .initializer(initializer)
                .build()
        )
        .addFunction(
            FunSpec.builder("handler")
                .addKdoc("A [%T] over [functions], ready to be installed on the bridge.\n", Runtime.FunctionHandler)
                .returns(Runtime.FunctionHandler)
                .addCode(CodeBlock.of("return %T(functions)\n", Runtime.FunctionHandler))
                .build()
        )
        .build()
}
