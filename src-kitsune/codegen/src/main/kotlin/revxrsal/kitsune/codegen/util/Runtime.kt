package revxrsal.kitsune.codegen.util

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName

/**
 * The `:app` runtime types that generated code calls into.
 *
 * Named rather than referenced, for the same cycle reason as [Annotations]: the
 * processor cannot depend on the module it processes. KotlinPoet only needs the
 * name to emit a correct import, and the Kotlin compiler resolves it for real
 * when it compiles the generated file, so a rename here surfaces as an
 * unresolved reference in generated code, not as a silent miscompile.
 */
object Runtime {

    const val IPC_PACKAGE = "revxrsal.kitsune.ipc"
    const val FUNCTIONS_PACKAGE = "revxrsal.kitsune.functions"
    const val EVENTS_PACKAGE = "revxrsal.kitsune.event"

    /**
     * Base class of every entry point. Constructing one is what installs the
     * `application` singleton the bridge dispatches through, which is why the
     * generated Rust calls a constructor and nothing else.
     */
    const val APPLICATION = "revxrsal.kitsune.app.KitsuneApplication"

    /** The configured `Cbor` instance both sides of the bridge encode with. */
    val Codec = MemberName(IPC_PACKAGE, "KitsuneCbor")

    /** The `null` that fills every synthetic method's trailing marker slot. */
    val DefaultConstructorMarker = MemberName(IPC_PACKAGE, "DEFAULT_CONSTRUCTOR_MARKER")

    val FunctionHandler = ClassName(FUNCTIONS_PACKAGE, "FunctionHandler")
    val EventHandler = ClassName(EVENTS_PACKAGE, "EventHandler")

    /** The sealed registry entry, and its two shapes. */
    val ExportedFunction = ClassName(FUNCTIONS_PACKAGE, "ExportedFunction")
    val BlockingFunction = ExportedFunction.nestedClass("Blocking")
    val SuspendingFunction = ExportedFunction.nestedClass("Suspending")
}

/** `kotlin.coroutines.Continuation`, the parameter the compiler appends to every suspend function. */
val ContinuationClass = ClassName("kotlin.coroutines", "Continuation")

/**
 * `suspendCoroutineUninterceptedOrReturn`, the intrinsic that hands a suspend
 * function its caller's continuation.
 *
 * "Unintercepted" is correct rather than a shortcut: interception happens once,
 * where the coroutine is started, and the compiler passes the raw continuation
 * at every ordinary suspend call site too. Using the intercepted variant here
 * would add a dispatch per call that a direct Kotlin call would not have.
 */
val SuspendIntrinsic = MemberName("kotlin.coroutines.intrinsics", "suspendCoroutineUninterceptedOrReturn")
