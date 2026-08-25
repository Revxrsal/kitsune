package revxrsal.kitsune.codegen.event

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.writeTo
import revxrsal.kitsune.codegen.functions.ExportedFun
import revxrsal.kitsune.codegen.functions.callSite
import revxrsal.kitsune.codegen.util.RESERVED_PACKAGE
import revxrsal.kitsune.codegen.util.Runtime
import revxrsal.kitsune.codegen.util.SerializerFunction

private const val FILE_NAME = "events"

/**
 * An `@ExportEvent` class, paired with the id the host addresses it by.
 *
 * The id defaults to the simple class name rather than the qualified one: it is
 * a wire identifier shared with Rust, and the Kotlin package structure is not
 * something the other side should have to mirror.
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

/**
 * Writes `revxrsal.kitsune.generated.events.kt`: the `GeneratedEvents` object
 * that registers every `@ExportEvent` type and wires every `@Listener` to it.
 *
 * Registration is emitted as imperative statements against an [Runtime.EventHandler]
 * rather than as a map literal, because the two halves are of different kinds —
 * a deserializer per event, a callback per listener, several callbacks allowed
 * per event — and a builder call expresses that without the generated file
 * having to name intermediate collection types.
 */
fun writeEventsFile(
    codeGenerator: CodeGenerator,
    events: List<ExportedEvent>,
    listeners: List<RegisteredListener>,
) {
    val register = FunSpec.builder("register")
        .addKdoc("Registers every exported event type and listener on [handler].\n")
        .addParameter("handler", Runtime.EventHandler)

    for (event in events) {
        register.addOriginatingKSFile(event.declaration.containingFile!!)
        register.addStatement(
            "handler.addEvent<%1T>(id·=·%2S, deserializer·=·%3M<%1T>())",
            event.className,
            event.id,
            SerializerFunction,
        )
    }

    for (listener in listeners) {
        listener.function.function.containingFile?.let(register::addOriginatingKSFile)
        // A suspending listener cannot run inside `dispatch`, which is called
        // from the host's synchronous thread — it is registered separately so
        // the handler can launch it into the scope instead.
        val registration =
            if (listener.function.isSuspend) "suspendingListener" else "listener"
        register.beginControlFlow(
            "handler.%L<%T>(id·=·%S) { event ->",
            registration,
            listener.eventType,
            listener.id,
        )
        register.addStatement("%L(event)", listener.function.callSite)
        register.endControlFlow()
    }

    val generated = TypeSpec.objectBuilder("GeneratedEvents")
        .addKdoc(
            "Every `@ExportEvent` type and `@Listener` function found in this module.\n" +
                "Generated — do not edit.\n"
        )
        .addFunction(register.build())
        .addFunction(
            FunSpec.builder("handler")
                .addKdoc("A fully wired [%T], ready to be installed on the bridge.\n", Runtime.EventHandler)
                .returns(Runtime.EventHandler)
                .addCode(CodeBlock.of("return %T().also(::register)\n", Runtime.EventHandler))
                .build()
        )
        .build()

    FileSpec.builder(RESERVED_PACKAGE, FILE_NAME)
        .addType(generated)
        .build()
        .writeTo(codeGenerator = codeGenerator, aggregating = true)
}
