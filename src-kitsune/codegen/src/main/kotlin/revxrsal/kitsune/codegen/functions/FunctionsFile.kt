package revxrsal.kitsune.codegen.functions

import com.google.devtools.ksp.processing.CodeGenerator
import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.ksp.writeTo
import revxrsal.kitsune.codegen.util.ExperimentalSerializationApiClass
import revxrsal.kitsune.codegen.util.JvmFieldClass
import revxrsal.kitsune.codegen.util.RESERVED_PACKAGE
import revxrsal.kitsune.codegen.util.Runtime
import revxrsal.kitsune.codegen.util.SerializerCache

/** File name (without extension) of the aggregated functions file. */
private const val FILE_NAME = "functions"

/**
 * An `@ExportFunction` at the ordinal the wire addresses it by.
 *
 * The ordinal is not stored on it: it is the entry's index in the list handed to
 * [writeFunctionsFile], which is what makes the generated tables and the
 * TypeScript bindings line up without either side carrying the number around.
 */
class ExportedEntry(val exportedName: String, val function: ExportedFun)

/**
 * Writes `revxrsal.kitsune.generated.functions.kt`: one wrapper per
 * `@ExportFunction`, the argument descriptor and decoder each one reads its
 * payload with, and the `GeneratedFunctions` object indexing them by ordinal.
 *
 * One file rather than one per function, and one write rather than one per
 * round. A `CodeGenerator` refuses to create the same output path twice, so the
 * aggregate can only be emitted once the full set is known, which is also why
 * the processor collects everything before calling this.
 *
 * `aggregating = true` tells KSP's incremental machinery that this output
 * depends on the whole set of annotated sources, so adding an export elsewhere
 * invalidates it. Marking it isolating instead would leave the tables stale,
 * and, now that dispatch is positional, stale means *misrouted* rather than
 * merely incomplete.
 */
fun writeFunctionsFile(
    codeGenerator: CodeGenerator,
    exported: List<ExportedEntry>,
) {
    val file = FileSpec.builder(RESERVED_PACKAGE, FILE_NAME)

    // The hand-written argument decoders are built out of
    // buildClassSerialDescriptor and decodeStructure, which are still marked
    // experimental. Opted into for the whole file rather than per declaration:
    // every wrapper that takes arguments needs it, and a file-level opt-in
    // cannot be mistaken for a claim about any one of them.
    file.addAnnotation(
        AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
            .addMember("%T::class", ExperimentalSerializationApiClass)
            .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
            .build()
    )

    // One serializer per distinct type, shared across every wrapper in the file
    // rather than rebuilt at each call site. Declared after the wrappers have
    // asked for them, so the file only carries the ones actually used.
    val serializers = SerializerCache()
    for (entry in exported) {
        entry.function.addTo(file, serializers)
    }
    serializers.addTo(file)

    file.addType(generatedFunctionsObject(exported))
    file.build().writeTo(codeGenerator = codeGenerator, aggregating = true)
}

/**
 * The registry the host dispatches through.
 *
 * Two flat arrays indexed by ordinal rather than a map keyed by name. The
 * payload arrives carrying the ordinal, so dispatch is a bounds check and a
 * load, with no string to decode out of the frame, no hash and no `equals`.
 * [names] is carried alongside purely so a bad ordinal can be reported in terms
 * a person recognises.
 *
 * The wrapper each entry points at is named after the *declaration*, while the
 * name at the same index is the exported one: `@ExportFunction(name = ...)` can
 * differ from the function's own name, and only the exported one is on the wire.
 */
private fun generatedFunctionsObject(exported: List<ExportedEntry>): TypeSpec {
    val namesType = ARRAY.parameterizedBy(STRING)
    val functionsType = ARRAY.parameterizedBy(Runtime.ExportedFunction)

    val names = buildCodeBlock {
        add("arrayOf(")
        if (exported.isNotEmpty()) {
            add("\n⇥")
            for (entry in exported) add("%S,\n", entry.exportedName)
            add("⇤")
        }
        add(")")
    }

    val functions = buildCodeBlock {
        add("arrayOf(")
        if (exported.isNotEmpty()) {
            add("\n⇥")
            for (entry in exported) {
                // Which of the two wrappers this is has to be recorded here: the
                // host cannot call a suspending export the way it calls a plain
                // one, and erasing the difference into one function type would
                // leave FunctionHandler unable to tell them apart.
                val shape = if (entry.function.isSuspend) {
                    Runtime.SuspendingFunction
                } else {
                    Runtime.BlockingFunction
                }
                add("%T(::%L),\n", shape, entry.function.name)
            }
            add("⇤")
        }
        add(")")
    }

    return TypeSpec.objectBuilder("GeneratedFunctions")
        .addKdoc(
            "Every function annotated `@ExportFunction`, indexed by the ordinal the host\n" +
                "dispatches with. Generated. Do not edit.\n"
        )
        .addProperty(
            PropertySpec.builder("names", namesType)
                .addKdoc(
                    "The exported name at each ordinal. Sorted, because that is how the\n" +
                        "ordinals were handed out, so a name maps back through `binarySearch`.\n"
                )
                .addAnnotation(JvmFieldClass)
                .initializer(names)
                .build()
        )
        .addProperty(
            PropertySpec.builder("functions", functionsType, KModifier.PRIVATE)
                .initializer(functions)
                .build()
        )
        .addFunction(
            FunSpec.builder("handler")
                .addKdoc(
                    "A [%T] over the exported functions, ready to be installed on the bridge.\n",
                    Runtime.FunctionHandler,
                )
                .returns(Runtime.FunctionHandler)
                .addCode(CodeBlock.of("return %T(functions, names)\n", Runtime.FunctionHandler))
                .build()
        )
        .build()
}
