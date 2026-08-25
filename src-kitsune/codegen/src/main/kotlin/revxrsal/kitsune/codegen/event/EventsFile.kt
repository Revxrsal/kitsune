package revxrsal.kitsune.codegen.event

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.writeTo
import revxrsal.kitsune.codegen.functions.ExportedFun
import revxrsal.kitsune.codegen.functions.callSite
import revxrsal.kitsune.codegen.util.DeserializationStrategyClass
import revxrsal.kitsune.codegen.util.JvmFieldClass
import revxrsal.kitsune.codegen.util.RESERVED_PACKAGE
import revxrsal.kitsune.codegen.util.Runtime
import revxrsal.kitsune.codegen.util.SerializerFunction

private const val FILE_NAME = "events"

/**
 * An `@ExportEvent` class, paired with the id the host addresses it by.
 *
 * The id defaults to the simple class name rather than the qualified one: it is
 * a wire identifier shared with Rust, and the Kotlin package structure is not
 * something the other side should have to mirror. What actually travels is the
 * event's *ordinal* — its index in the list handed to [writeEventsFile] — and
 * the id survives only so a mismatch can be reported in readable terms.
 */
class ExportedEvent(
    val className: ClassName,
    val qualifiedName: String,
    val id: String,
    val declaration: KSClassDeclaration,
)

/** A `@Listener` function, resolved to the event it handles. */
class RegisteredListener(
    val function: ExportedFun,
    val eventType: ClassName,
    val id: String,
)

/** `(Any) -> Unit` — the shape every listener is erased to in the tables. */
private val ListenerType = LambdaTypeName.get(parameters = arrayOf(ANY), returnType = UNIT)

/** The same, `suspend`. */
private val SuspendingListenerType = ListenerType.copy(suspending = true)

/**
 * Writes `revxrsal.kitsune.generated.events.kt`: the `GeneratedEvents` object
 * holding every `@ExportEvent` type and the `@Listener`s wired to it.
 *
 * Everything is emitted as flat arrays indexed by ordinal, built once as object
 * initialisers. The host sends the ordinal in the payload, so dispatch is a
 * bounds check and two array loads — where a map keyed by id meant decoding a
 * string out of the frame and hashing it on every event, for a table whose
 * contents are fully known at generation time.
 *
 * The arrays are parallel by construction: [events] arrives in ordinal order,
 * and the listener rows are built by walking it, so index *i* is the same event
 * in all four.
 */
fun writeEventsFile(
    codeGenerator: CodeGenerator,
    events: List<ExportedEvent>,
    listeners: List<RegisteredListener>,
) {
    val byEvent = listeners.groupBy { it.id }

    val generated = TypeSpec.objectBuilder("GeneratedEvents")
        .addKdoc(
            "Every `@ExportEvent` type and `@Listener` function found in this module,\n" +
                "indexed by the ordinal the host addresses them with.\n" +
                "Generated — do not edit.\n"
        )

    for (event in events) {
        generated.addOriginatingKSFile(event.declaration.containingFile!!)
    }
    for (listener in listeners) {
        listener.function.function.containingFile?.let(generated::addOriginatingKSFile)
    }

    generated.addProperty(
        PropertySpec.builder("ids", ARRAY.parameterizedBy(STRING))
            .addKdoc(
                "The event id at each ordinal. Sorted, because that is how the ordinals\n" +
                        "were handed out — so an id maps back through `binarySearch`.\n"
            )
            .addAnnotation(JvmFieldClass)
            .initializer(table(events) { CodeBlock.of("%S", it.id) })
            .build()
    )

    generated.addProperty(
        PropertySpec.builder("idsByClassName", ARRAY.parameterizedBy(STRING))
            .addKdoc(
                "The event id at each ordinal. Sorted, because that is how the ordinals\n" +
                        "were handed out — so an id maps back through `binarySearch`.\n"
            )
            .addAnnotation(JvmFieldClass)
            .initializer(table(events) { CodeBlock.of("%S", it.qualifiedName) })
            .build()
    )

    val deserializerType = DeserializationStrategyClass.parameterizedBy(STAR)
    generated.addProperty(
        PropertySpec.builder("deserializers", ARRAY.parameterizedBy(deserializerType), KModifier.PRIVATE)
            .initializer(
                table(events, deserializerType) {
                    CodeBlock.of("%M<%T>()", SerializerFunction, it.className)
                }
            )
            .build()
    )

    // A suspending listener cannot run inside `dispatch`, which is called from
    // the host's synchronous thread — it goes in the second table so the handler
    // can launch it into the scope instead.
    generated.addProperty(listenerTable("listeners", ListenerType, events) { row ->
        byEvent[row.id].orEmpty().filterNot { it.function.isSuspend }
    })
    generated.addProperty(listenerTable("suspendingListeners", SuspendingListenerType, events) { row ->
        byEvent[row.id].orEmpty().filter { it.function.isSuspend }
    })

    generated.addFunction(
        FunSpec.builder("handler")
            .addKdoc("A fully wired [%T], ready to be installed on the bridge.\n", Runtime.EventHandler)
            .returns(Runtime.EventHandler)
            .addCode(
                CodeBlock.of(
                    "return %T(ids, idsByClassName, deserializers, listeners, suspendingListeners)\n",
                    Runtime.EventHandler,
                )
            )
            .build()
    )

    FileSpec.builder(RESERVED_PACKAGE, FILE_NAME)
        .addType(generated.build())
        .build()
        .writeTo(codeGenerator = codeGenerator, aggregating = true)
}

/**
 * One row per event, each holding that event's listeners.
 *
 * Every listener is erased to `(Any) -> Unit` and casts its argument back. The
 * cast is a `checkcast` the JIT folds away once the call site is monomorphic —
 * and it is the price of the tables being one type wide, which is what lets
 * dispatch index into them without a lookup.
 */
private fun listenerTable(
    name: String,
    type: LambdaTypeName,
    events: List<ExportedEvent>,
    rowOf: (ExportedEvent) -> List<RegisteredListener>,
): PropertySpec {
    val rowType = ARRAY.parameterizedBy(type)
    val initializer = table(events, rowType) { event ->
        table(rowOf(event), type) { listener ->
            CodeBlock.of(
                "{ event -> %L(event·as·%T) }",
                listener.function.callSite,
                listener.eventType,
            )
        }
    }
    return PropertySpec.builder(name, ARRAY.parameterizedBy(rowType), KModifier.PRIVATE)
        .initializer(initializer)
        .build()
}

/**
 * An `arrayOf(...)` over [items], one entry per line.
 *
 * [elementType] is spelled out rather than left to inference: a row can be
 * empty, and `arrayOf()` with nothing in it has no type to infer from.
 */
private fun <T> table(
    items: List<T>,
    elementType: TypeName? = null,
    entry: (T) -> CodeBlock,
): CodeBlock = buildCodeBlock {
    if (elementType == null) add("arrayOf(") else add("arrayOf<%T>(", elementType)
    if (items.isNotEmpty()) {
        add("\n⇥")
        for (item in items) add("%L,\n", entry(item))
        add("⇤")
    }
    add(")")
}
