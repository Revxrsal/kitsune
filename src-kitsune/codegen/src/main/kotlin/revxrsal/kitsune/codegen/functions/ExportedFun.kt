package revxrsal.kitsune.codegen.functions

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.NameAllocator
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import revxrsal.kitsune.codegen.util.ContinuationClass
import revxrsal.kitsune.codegen.util.DefaultConstructorMarkerType
import revxrsal.kitsune.codegen.util.INT_TYPE_BLOCK
import revxrsal.kitsune.codegen.util.JavaObjectType
import revxrsal.kitsune.codegen.util.LambdaMetafactoryClass
import revxrsal.kitsune.codegen.util.Masker
import revxrsal.kitsune.codegen.util.MethodHandlesClass
import revxrsal.kitsune.codegen.util.MethodTypeClass
import revxrsal.kitsune.codegen.util.RESERVED_PACKAGE
import revxrsal.kitsune.codegen.util.Runtime
import revxrsal.kitsune.codegen.util.SERIALIZABLE
import revxrsal.kitsune.codegen.util.SerializerFunction
import revxrsal.kitsune.codegen.util.SuspendIntrinsic
import revxrsal.kitsune.codegen.util.addComment
import revxrsal.kitsune.codegen.util.asReturnTypeBlock
import revxrsal.kitsune.codegen.util.enclosingObject
import revxrsal.kitsune.codegen.util.primaryConstructor

/** The local the decoded argument holder is bound to in generated code. */
private const val HOLDER = "args"

/**
 * An `@ExportFunction` (or `@Listener`) declaration, with everything the
 * generated wrapper needs derived from it.
 *
 * [containingJavaClass] is the JVM class the function compiles into —
 * `FooKt` for a top-level function, the object's own class for a member. It is
 * only needed to locate the synthetic `$default` method, and KSP has to supply
 * it because that name is a compiler detail with no Kotlin-level equivalent.
 */
class ExportedFun(
    val function: KSFunctionDeclaration,
    val containingJavaClass: String,
) {

    val name = function.simpleName.asString()

    val qualifiedName = function.qualifiedName!!.asString()

    val returnType = function.returnType?.resolve()?.toTypeName() ?: UNIT

    val parameters = function.parameters.map { ExportedParameter(it) }

    /** The enclosing `object`, or null when the function is top-level. */
    val owner: KSClassDeclaration? = function.enclosingObject()

    val hasDefaultParameters = parameters.any { it.hasDefault }

    val takesArgs = parameters.isNotEmpty()

    /**
     * Whether the target is a `suspend` function.
     *
     * This changes the synthetic method's JVM signature in three places — an
     * appended `Continuation`, an `Object` return regardless of what Kotlin
     * declares, and a call that has to hand over the caller's continuation — so
     * it is threaded through rather than re-derived at each site.
     */
    val isSuspend = Modifier.SUSPEND in function.modifiers
}

/**
 * How generated code names the real function at the call site.
 *
 * Spelled out in full rather than emitted as a `MemberName`, which would add an
 * import. Every wrapper shares its name with the function it wraps, and an
 * explicit import wins over a same-package declaration in Kotlin resolution — so
 * importing the target silently shadows the wrapper, and `::name` in the
 * registry then resolves to the user's function with the wrong signature.
 */
val ExportedFun.callSite: CodeBlock
    get() = owner?.let { CodeBlock.of("%T.%L", it.toClassName(), name) }
        ?: CodeBlock.of("%L", qualifiedName)

/** The `@Serializable` holder carrying this function's arguments over the bridge. */
val ExportedFun.holderClassName get() = "Args_$name"

/** The functional interface the synthetic `$default` method is bound to. */
private val ExportedFun.syntheticInterface
    get() = ClassName(RESERVED_PACKAGE, "Default_$name")

/** The `private val` holding the bound synthetic method. */
private val ExportedFun.syntheticProperty get() = "`synthetic\$$name`"

/** The name the Kotlin compiler gives the default-substituting synthetic method. */
private val ExportedFun.syntheticMethod get() = "$name\$default"

/**
 * Rejects shapes the generated wrapper cannot express, and returns whether the
 * function survived.
 *
 * Everything here is an error rather than a deferral: these are all decidable
 * from the declaration itself, so waiting for another round would only turn a
 * precise message into KSP's generic "unable to process" at the end of the build.
 */
fun ExportedFun.checkExportable(logger: KSPLogger, annotation: String): Boolean {
    var ok = true

    fun reject(message: String, node: KSNode = function) {
        ok = false
        logger.error(message, node)
    }

    if (function.typeParameters.isNotEmpty()) {
        reject("$annotation does not support generic functions: the host dispatches by name and has no type arguments to supply.")
    }
    if (function.extensionReceiver != null) {
        reject("$annotation does not support extension functions.")
    }
    if (owner == null && function.parentDeclaration != null) {
        reject(
            "$annotation must be on a top-level function or a member of an object. The generated " +
                "code calls it from a static context and has no instance to call it on."
        )
    }
    for (parameter in parameters) {
        if (parameter.isVararg) {
            reject("$annotation does not support vararg parameters.")
        }
        // See ExportedParameter.holderType: absence and an explicit null decode
        // to the same thing, so the two readings must agree on what to do.
        if (parameter.hasDefault && parameter.isNullable) {
            logger.warn(
                "Parameter '${parameter.name}' of $annotation function '$name' is both nullable " +
                    "and defaulted. An explicit null from the host is indistinguishable from an " +
                    "omitted argument, so the declared default is used for both.",
                function,
            )
        }
    }
    return ok
}

/**
 * Writes this function's argument holder, default-binding machinery and wrapper
 * into the aggregated functions file.
 */
fun ExportedFun.addTo(file: FileSpec.Builder) {
    val wrapper = FunSpec.builder(name)
        .addOriginatingKSFile(function.containingFile!!)
        .addParameter("request", ByteArray::class)
        .returns(ByteArray::class)
    // The wrapper mirrors what it wraps: a suspending export needs a suspending
    // caller, and the registry keeps the two apart precisely so this is visible.
    if (isSuspend) wrapper.addModifiers(KModifier.SUSPEND)

    val code = CodeBlock.builder()

    // No arguments: nothing to decode, nothing to mask.
    if (!takesArgs) {
        callDirectly(code)
        file.addFunction(wrapper.addCode(code.build()).build())
        return
    }

    file.addType(createHolderClass())
    code.addStatement(
        "val %L = %M.decodeFromByteArray(%M<%L>(), request)",
        HOLDER,
        Runtime.Codec,
        SerializerFunction,
        holderClassName,
    )

    // Every argument is mandatory, so the direct call always applies.
    if (!hasDefaultParameters) {
        callDirectly(code)
        file.addFunction(wrapper.addCode(code.build()).build())
        return
    }

    // The mask locals and the synthetic interface's parameters share their names
    // with the declared parameters, so both are allocated from one namespace —
    // otherwise a parameter called `mask0` or `marker` collides with the
    // machinery generated around it.
    val names = NameAllocator()
    parameters.forEach { names.newName(it.name) }
    names.newName(HOLDER)
    val masker = Masker(count = parameters.size, nameAllocator = names)
    val receiverName = names.newName("receiver")
    val markerName = names.newName("marker")
    val continuationName = names.newName("continuation")

    file.addType(createSyntheticInterface(masker, receiverName, markerName, continuationName))
    file.addProperty(createSyntheticProperty(masker))

    masker.declare(code)
    computeMask(code, masker)

    code.beginControlFlow("if (%L)", masker.allArgumentsSupplied())
    code.addComment("Every defaulted parameter was supplied; skip the synthetic method.")
    callDirectly(code)
    code.endControlFlow()

    callThroughSynthetic(code, masker, continuationName)
    file.addFunction(wrapper.addCode(code.build()).build())
}

/**
 * Clears one mask bit per supplied argument.
 *
 * The walk covers *every* parameter, not just the defaulted ones, because bit
 * positions are parameter positions — skipping a mandatory parameter would shift
 * every subsequent bit and silently substitute the wrong defaults.
 */
private fun ExportedFun.computeMask(code: CodeBlock.Builder, masker: Masker) {
    for (parameter in parameters) {
        if (parameter.hasDefault) {
            code.beginControlFlow("if (%L.%L != null)", HOLDER, parameter.name)
            masker.clearCurrentBit(code)
            code.endControlFlow()
        }
        masker.advance()
    }
}

/** Calls the real function by name, with every argument named. */
private fun ExportedFun.callDirectly(code: CodeBlock.Builder) {
    val arguments = parameters
        .map { CodeBlock.of("%L·=·%L", it.name, it.directAccess(HOLDER)) }
        .joinToCode(", ")
    code.emitResult(this, CodeBlock.of("%L(%L)", callSite, arguments))
}

/**
 * Calls the synthetic `$default` method so the compiler applies the defaults.
 *
 * For a suspending target the call is wrapped in
 * `suspendCoroutineUninterceptedOrReturn`, which is the whole trick: the
 * synthetic returns either the result or the `COROUTINE_SUSPENDED` sentinel, and
 * that is exactly the contract the intrinsic's block is defined by. So the
 * caller's own continuation goes straight into the `Continuation` slot and the
 * sentinel is propagated untouched — the same handoff the compiler emits for an
 * ordinary suspend call, with no extra frame, no wrapper continuation and no
 * dispatch.
 */
private fun ExportedFun.callThroughSynthetic(
    code: CodeBlock.Builder,
    masker: Masker,
    continuationName: String,
) {
    val arguments = buildList {
        // A member of an object compiles to a static synthetic taking the
        // instance as its first argument.
        owner?.let { add(CodeBlock.of("%T", it.toClassName())) }
        parameters.mapTo(this) { it.syntheticAccess(HOLDER) }
        // The compiler appends the continuation after the declared parameters
        // and before the masks. It is not a declared parameter, so it consumes
        // no mask bit.
        if (isSuspend) add(CodeBlock.of("%L", continuationName))
        add(masker.asArguments())
        add(CodeBlock.of("%M", Runtime.DefaultConstructorMarker))
    }.joinToCode(", ")

    code.addComment("Some arguments were omitted; let the compiler's synthetic method fill them in.")

    if (!isSuspend) {
        code.emitResult(this, CodeBlock.of("%L.invoke(%L)", syntheticProperty, arguments))
        return
    }

    val binding = if (returnType == UNIT) "" else "val result = "
    code.beginControlFlow(
        "%L%M<%T> { %L ->",
        binding,
        SuspendIntrinsic,
        returnType,
        continuationName,
    )
    code.addStatement("%L.invoke(%L)", syntheticProperty, arguments)
    code.endControlFlow()
    code.emitEncodedReturn(this)
}

/**
 * Emits [call] and encodes its result.
 *
 * `Unit` is encoded as zero bytes rather than through its serializer: it carries
 * no information, and an empty reply is what the host can most cheaply detect.
 * It also must not be bound to a local — a `Unit`-typed `val result` is an
 * unused-variable warning in every generated wrapper that has one.
 */
private fun CodeBlock.Builder.emitResult(function: ExportedFun, call: CodeBlock) {
    if (function.returnType == UNIT) {
        addStatement("%L", call)
    } else {
        addStatement("val result = %L", call)
    }
    emitEncodedReturn(function)
}

/** Returns `result`, encoded — or zero bytes when there is nothing to encode. */
private fun CodeBlock.Builder.emitEncodedReturn(function: ExportedFun) {
    if (function.returnType == UNIT) {
        addStatement("return ByteArray(0)")
    } else {
        addStatement(
            "return %M.encodeToByteArray(%M<%T>(), result)",
            Runtime.Codec,
            SerializerFunction,
            function.returnType,
        )
    }
}

/**
 * The functional interface the synthetic `$default` method is bound to.
 *
 * Its signature mirrors the synthetic's exactly — receiver first for an object
 * member, then the declared parameters, then one `Int` mask per 32 of them, then
 * the marker. That exactness is the point: it makes the call site a plain
 * interface call the JIT can inline, with primitives passed unboxed and no
 * `Array<Any?>` built per invocation, which is what `Method.invoke` costs.
 *
 * Reference-typed parameters are widened to nullable. At the JVM level that
 * changes nothing — the descriptor is the same — but an omitted argument is
 * passed as `null` here, and a non-null Kotlin parameter would reject it at
 * compile time.
 *
 * For a suspending target two things differ, and both come from the JVM shape of
 * a suspend function rather than from anything Kotlin shows in the source:
 *
 * - a raw `Continuation` sits between the declared parameters and the masks;
 * - the return type is `Any?`, because a suspend function returns `Object` at
 *   the JVM level — its declared type *or* the `COROUTINE_SUSPENDED` sentinel.
 *   That holds even when it declares `Unit`, so this interface method is not
 *   `Unit`-returning and must not be generated as one.
 *
 * The SAM itself is not `suspend`. A `suspend` member would make the compiler
 * append a *second* continuation of its own, and the descriptor would stop
 * matching.
 */
private fun ExportedFun.createSyntheticInterface(
    masker: Masker,
    receiverName: String,
    markerName: String,
    continuationName: String,
): TypeSpec {
    val invoke = FunSpec.builder("invoke").addModifiers(KModifier.ABSTRACT)
    owner?.let { invoke.addParameter(receiverName, it.toClassName()) }
    for (parameter in parameters) {
        invoke.addParameter(parameter.name, parameter.syntheticType)
    }
    if (isSuspend) {
        invoke.addParameter(continuationName, ContinuationClass.parameterizedBy(STAR))
    }
    for (maskName in masker.maskNames) {
        invoke.addParameter(maskName, INT)
    }
    invoke.addParameter(markerName, ANY.copy(nullable = true))
    invoke.returns(if (isSuspend) ANY.copy(nullable = true) else returnType)

    return TypeSpec.funInterfaceBuilder(syntheticInterface)
        .addModifiers(KModifier.PRIVATE)
        .addKdoc("Binds `%L.%L`.\n", containingJavaClass, syntheticMethod)
        .addFunction(invoke.build())
        .build()
}

/**
 * Binds the synthetic method to [syntheticInterface] through
 * `LambdaMetafactory`, the same machinery the JVM uses for `invokedynamic`
 * lambdas — so the result is an ordinary object with a direct call to the target,
 * not a reflective dispatch.
 *
 * `lazy` because the binding costs a lookup and a spun class: a function that is
 * never called with omitted arguments never pays for it, and nothing here runs
 * during class initialisation, which matters for a module whose classes are
 * loaded and linked ahead of time by the AOT training pass.
 */
private fun ExportedFun.createSyntheticProperty(masker: Masker): PropertySpec {
    val parameterTypes = buildList {
        owner?.let { add(CodeBlock.of("%T::class.java", it.toClassName())) }
        parameters.mapTo(this) { it.typeBlock }
        // Erased in the synthetic's descriptor, so the raw class is what matches.
        if (isSuspend) add(CodeBlock.of("%T::class.java", ContinuationClass))
        // One mask parameter per 32 declared parameters, then the marker.
        repeat(masker.maskCount) { add(INT_TYPE_BLOCK) }
        add(DefaultConstructorMarkerType)
    }.joinToCode(", ")

    // A suspend function returns Object at the JVM level whatever it declares —
    // including Unit, where a non-suspend function would return void.
    val returnClass = if (isSuspend) JavaObjectType else returnType.asReturnTypeBlock()

    val code = buildCodeBlock {
        beginControlFlow("lazy")
        addStatement("val lookup = %T.lookup()", MethodHandlesClass)
        addStatement(
            "val signature = %T.methodType(%L, %L)",
            MethodTypeClass,
            returnClass,
            parameterTypes,
        )
        addComment("Resolved by name: the synthetic overload has no Kotlin-level declaration.")
        addStatement(
            "val implementation = lookup.findStatic(Class.forName(%S), %S, signature)",
            containingJavaClass,
            syntheticMethod,
        )
        addStatement(
            "%T.metafactory(lookup, %S, %T.methodType(%T::class.java), signature, implementation, signature)",
            LambdaMetafactoryClass,
            "invoke",
            MethodTypeClass,
            syntheticInterface,
        )
        // invokeWithArguments rather than invokeExact: the latter is
        // signature-polymorphic, so its descriptor comes from the call site's
        // static types, and a mismatch is a WrongMethodTypeException at runtime
        // instead of an error here. This runs once, so the boxing is free.
        addStatement("    .target.invokeWithArguments() as %T", syntheticInterface)
        endControlFlow()
    }
    return PropertySpec.builder(syntheticProperty, syntheticInterface, KModifier.PRIVATE)
        .delegate(code)
        .build()
}

/** The `@Serializable` class the host's argument payload decodes into. */
private fun ExportedFun.createHolderClass(): TypeSpec =
    TypeSpec.classBuilder(holderClassName)
        .addAnnotation(SERIALIZABLE)
        .addOriginatingKSFile(function.containingFile!!)
        .primaryConstructor(parameters.map { it.toHolderParameter() })
        .build()
