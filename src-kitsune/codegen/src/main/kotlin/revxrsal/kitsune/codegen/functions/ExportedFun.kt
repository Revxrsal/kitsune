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
import revxrsal.kitsune.codegen.util.BuildClassSerialDescriptor
import revxrsal.kitsune.codegen.util.CompositeDecoderClass
import revxrsal.kitsune.codegen.util.ContinuationClass
import revxrsal.kitsune.codegen.util.DecodeStructure
import revxrsal.kitsune.codegen.util.DecoderClass
import revxrsal.kitsune.codegen.util.DefaultConstructorMarkerType
import revxrsal.kitsune.codegen.util.SerializerStrategyClass
import revxrsal.kitsune.codegen.util.INT_TYPE_BLOCK
import revxrsal.kitsune.codegen.util.JavaObjectType
import revxrsal.kitsune.codegen.util.LambdaMetafactoryClass
import revxrsal.kitsune.codegen.util.Masker
import revxrsal.kitsune.codegen.util.MethodHandlesClass
import revxrsal.kitsune.codegen.util.MethodTypeClass
import revxrsal.kitsune.codegen.util.RESERVED_PACKAGE
import revxrsal.kitsune.codegen.util.Runtime
import revxrsal.kitsune.codegen.util.SerialDescriptorClass
import revxrsal.kitsune.codegen.util.SerializationExceptionClass
import revxrsal.kitsune.codegen.util.SerializerCache
import revxrsal.kitsune.codegen.util.SuspendIntrinsic
import revxrsal.kitsune.codegen.util.addComment
import revxrsal.kitsune.codegen.util.asReturnTypeBlock
import revxrsal.kitsune.codegen.util.enclosingObject

/** The local the argument decoder is bound to in generated code. */
private const val DECODER = "args"

/** The `descriptor` override every decoder carries; reserved before parameters are named. */
private const val DESCRIPTOR = "descriptor"

/**
 * An `@ExportFunction` (or `@Listener`) declaration, with everything the
 * generated wrapper needs derived from it.
 *
 * [containingJavaClass] is the JVM class the function compiles into:
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
     * This changes the synthetic method's JVM signature in three places (an
     * appended `Continuation`, an `Object` return regardless of what Kotlin
     * declares, and a call that has to hand over the caller's continuation), so
     * it is threaded through rather than re-derived at each site.
     */
    val isSuspend = Modifier.SUSPEND in function.modifiers
}

/**
 * How generated code names the real function at the call site.
 *
 * Spelled out in full rather than emitted as a `MemberName`, which would add an
 * import. Every wrapper shares its name with the function it wraps, and an
 * explicit import wins over a same-package declaration in Kotlin resolution, so
 * importing the target silently shadows the wrapper, and `::name` in the
 * registry then resolves to the user's function with the wrong signature.
 */
val ExportedFun.callSite: CodeBlock
    get() = owner?.let { CodeBlock.of("%T.%L", it.toClassName(), name) }
        ?: CodeBlock.of("%L", qualifiedName)

/**
 * The serial name of this function's argument descriptor.
 *
 * Only ever seen in a `SerializationException` message (there is no class by
 * this name any more), so it is named after the shape it describes rather than
 * after the wrapper.
 */
val ExportedFun.argumentsSerialName get() = "Args_$name"

/** The `private val` holding this function's argument descriptor. */
private val ExportedFun.descriptorProperty get() = "`descriptor\$$name`"

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
    }
    return ok
}

/**
 * Writes this function's argument descriptor and decoder, its default-binding
 * machinery and its wrapper into the aggregated functions file.
 */
fun ExportedFun.addTo(file: FileSpec.Builder, serializers: SerializerCache) {
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
        callDirectly(code, emptyMap(), serializers = serializers)
        file.addFunction(wrapper.addCode(code.build()).build())
        return
    }

    // Everything generated around the parameters shares their namespace, so all
    // of it is allocated from one allocator; otherwise a parameter called
    // `mask0` or `marker` collides with the machinery built around it.
    //
    // `descriptor` is reserved first because it is the one name that cannot
    // move: it is the `DeserializationStrategy` member the decoder overrides. A
    // parameter of that name is renamed to `descriptor_` on the decoder and goes
    // on carrying its declared name on the wire and at the call site.
    val names = NameAllocator()
    names.newName(DESCRIPTOR)
    val fields = parameters.associateWith { names.newName(it.name, it) }
    val decoderName = names.newName(DECODER)
    val masker = Masker(count = parameters.size, nameAllocator = names)
    val decoderParameter = names.newName("decoder")
    val indexName = names.newName("index")
    val receiverName = names.newName("receiver")
    val markerName = names.newName("marker")
    val continuationName = names.newName("continuation")

    file.addProperty(createDescriptorProperty())
    code.add(
        "val %L = %L\n",
        decoderName,
        createDecoder(masker, fields, decoderParameter, indexName, serializers),
    )
    code.addStatement("%M.decodeFromByteArray(%L, request)", Runtime.Codec, decoderName)
    checkRequiredArguments(code, masker, decoderName)

    // Every argument is mandatory, so the direct call always applies.
    if (!hasDefaultParameters) {
        callDirectly(code, fields, decoderName, serializers)
        file.addFunction(wrapper.addCode(code.build()).build())
        return
    }

    file.addType(createSyntheticInterface(masker, receiverName, markerName, continuationName))
    file.addProperty(createSyntheticProperty(masker))

    code.beginControlFlow("if (%L)", masker.allArgumentsSupplied("$decoderName."))
    code.addComment("Every defaulted parameter was supplied; skip the synthetic method.")
    callDirectly(code, fields, decoderName, serializers)
    code.endControlFlow()

    callThroughSynthetic(code, masker, fields, decoderName, continuationName, serializers)
    file.addFunction(wrapper.addCode(code.build()).build())
}

/**
 * Rejects a payload that left out a parameter with no default.
 *
 * The check exists because it is no longer free. A plugin-generated
 * `@Serializable` decoder raises `MissingFieldException` for a required property
 * on its own; a hand-written one is handed the element indices the payload
 * carried and nothing else, so the same guarantee has to be spelled out.
 * Without it, `add(a: Int, b: Int)` would answer `{a: 1}` with `b = 0` rather
 * than an error.
 *
 * It runs before the mask is consulted for anything else, which is what lets
 * [Masker.allArgumentsSupplied] keep meaning "every *defaulted* parameter was
 * supplied" even though mandatory parameters clear bits too: by this point
 * theirs are known to be clear.
 */
private fun ExportedFun.checkRequiredArguments(
    code: CodeBlock.Builder,
    masker: Masker,
    decoderName: String,
) {
    val required = parameters.withIndex().filter { (_, parameter) -> !parameter.hasDefault }
    if (required.isEmpty()) return

    // One `and` per mask on the happy path, however many parameters it covers.
    val condition = masker.requiredBits(required.map { it.index })
        .map { (maskName, bits) ->
            CodeBlock.of("%L.%L·and·0x%L.toInt()·!= 0", decoderName, maskName, Integer.toHexString(bits))
        }
        .joinToCode("·|| ")

    code.beginControlFlow("if (%L)", condition)
    code.beginControlFlow("val missing = buildList")
    for ((index, parameter) in required) {
        code.addStatement(
            "if (%L.%L and 0x%L.toInt() != 0) add(%S)",
            decoderName,
            masker.maskNameOf(index),
            Integer.toHexString(masker.bitOf(index)),
            parameter.name,
        )
    }
    code.endControlFlow()
    code.addStatement(
        "throw %T(%P)",
        SerializationExceptionClass,
        "Missing required argument(s) \$missing for '$name'.",
    )
    code.endControlFlow()
}

/**
 * The decoder for this function's arguments.
 *
 * Hand-written rather than left to `@Serializable`, for the one thing the
 * plugin-generated decoder cannot report: *which keys the payload carried*. It
 * yields a value per property, and an absent key and an explicit null both
 * arrive as `null`, so a parameter that is nullable and defaulted has no way
 * to tell "use the default" from "the host really means null". `decodeElementIndex`
 * visits only the keys that were actually present, so the mask falls out of the
 * decode that was happening anyway, exact and free.
 *
 * An anonymous object rather than a named class, with the decoded values as
 * fields on it rather than as captured locals. Captured `var`s would each be
 * boxed into a `Ref` (one allocation per parameter, per call, on top of the
 * decoder), whereas fields make it a single object the wrapper reads straight
 * out of. It is also why nothing here is a `KSerializer`: only [deserialize] is
 * ever reached, and `Unit` is the honest return type for a decoder that writes
 * its results into itself.
 */
private fun ExportedFun.createDecoder(
    masker: Masker,
    fields: Map<ExportedParameter, String>,
    decoderParameter: String,
    indexName: String,
    serializers: SerializerCache,
): TypeSpec {
    val body = buildCodeBlock {
        beginControlFlow("%L.%M(%L)", decoderParameter, DecodeStructure, DESCRIPTOR)
        beginControlFlow("while (true)")
        beginControlFlow("when (val %L = decodeElementIndex(%L))", indexName, DESCRIPTOR)
        for ((index, parameter) in parameters.withIndex()) {
            beginControlFlow("%L ->", index)
            addStatement(
                "%L·= %L",
                fields.getValue(parameter),
                parameter.readElement(index, name, serializers.nameFor(parameter.fieldType)),
            )
            masker.clearBit(this, index)
            endControlFlow()
        }
        addStatement("%T.DECODE_DONE -> break", CompositeDecoderClass)
        // Unreachable in practice: an unknown key is skipped rather than
        // reported, because KitsuneCbor is configured with ignoreUnknownKeys.
        addStatement(
            "else -> throw %T(%P)",
            SerializationExceptionClass,
            "Unexpected element index \$$indexName while decoding '$name'.",
        )
        endControlFlow()
        endControlFlow()
        endControlFlow()
    }

    return TypeSpec.anonymousClassBuilder()
        .addSuperinterface(SerializerStrategyClass.parameterizedBy(UNIT))
        .addProperties(parameters.map { it.toDecoderField(fields.getValue(it)) })
        .addProperties(masker.fields())
        .addProperty(
            PropertySpec.builder(DESCRIPTOR, SerialDescriptorClass, KModifier.OVERRIDE)
                .getter(
                    FunSpec.getterBuilder()
                        .addStatement("return %L", descriptorProperty)
                        .build()
                )
                .build()
        )
        .addFunction(
            FunSpec.builder("deserialize")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter(decoderParameter, DecoderClass)
                .addCode(body)
                .build()
        )
        .build()
}

/**
 * The descriptor naming this function's arguments, in declaration order.
 *
 * At file scope and `lazy` for the same reason as the synthetic binding: a
 * function that is never called never builds one, and nothing runs during class
 * initialisation, which matters for a module whose classes are linked ahead of
 * time by the AOT training pass.
 *
 * Element order is what the mask is indexed by (element *i* is parameter *i*),
 * so it must follow the declaration and not, say, the alphabet.
 */
private fun ExportedFun.createDescriptorProperty(): PropertySpec {
    val descriptor = buildCodeBlock {
        beginControlFlow("lazy")
        beginControlFlow("%M(%S)", BuildClassSerialDescriptor, argumentsSerialName)
        for (parameter in parameters) {
            addStatement("%L", parameter.toDescriptorElement())
        }
        endControlFlow()
        endControlFlow()
    }
    return PropertySpec.builder(descriptorProperty, SerialDescriptorClass, KModifier.PRIVATE)
        .addKdoc("The arguments of `%L`, as the host sends them.\n", qualifiedName)
        .delegate(descriptor)
        .build()
}

/** Calls the real function by name, with every argument named. */
private fun ExportedFun.callDirectly(
    code: CodeBlock.Builder,
    fields: Map<ExportedParameter, String>,
    decoderName: String = DECODER,
    serializers: SerializerCache,
) {
    val arguments = parameters
        .map { CodeBlock.of("%L·=·%L", it.name, it.directAccess(decoderName, fields.getValue(it))) }
        .joinToCode(", ")
    code.emitResult(this, CodeBlock.of("%L(%L)", callSite, arguments), serializers)
}

/**
 * Calls the synthetic `$default` method so the compiler applies the defaults.
 *
 * For a suspending target the call is wrapped in
 * `suspendCoroutineUninterceptedOrReturn`, which is the whole trick: the
 * synthetic returns either the result or the `COROUTINE_SUSPENDED` sentinel, and
 * that is exactly the contract the intrinsic's block is defined by. So the
 * caller's own continuation goes straight into the `Continuation` slot and the
 * sentinel is propagated untouched, the same handoff the compiler emits for an
 * ordinary suspend call, with no extra frame, no wrapper continuation and no
 * dispatch.
 */
private fun ExportedFun.callThroughSynthetic(
    code: CodeBlock.Builder,
    masker: Masker,
    fields: Map<ExportedParameter, String>,
    decoderName: String,
    continuationName: String,
    serializers: SerializerCache,
) {
    val arguments = buildList {
        // A member of an object compiles to a static synthetic taking the
        // instance as its first argument.
        owner?.let { add(CodeBlock.of("%T", it.toClassName())) }
        parameters.mapTo(this) { it.syntheticAccess(decoderName, fields.getValue(it)) }
        // The compiler appends the continuation after the declared parameters
        // and before the masks. It is not a declared parameter, so it consumes
        // no mask bit.
        if (isSuspend) add(CodeBlock.of("%L", continuationName))
        add(masker.asArguments("$decoderName."))
        add(CodeBlock.of("%M", Runtime.DefaultConstructorMarker))
    }.joinToCode(", ")

    code.addComment("Some arguments were omitted; let the compiler's synthetic method fill them in.")

    if (!isSuspend) {
        code.emitResult(this, CodeBlock.of("%L.invoke(%L)", syntheticProperty, arguments), serializers)
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
    code.emitEncodedReturn(this, serializers)
}

/**
 * Emits [call] and encodes its result.
 *
 * `Unit` is encoded as zero bytes rather than through its serializer: it carries
 * no information, and an empty reply is what the host can most cheaply detect.
 * It also must not be bound to a local, because a `Unit`-typed `val result` is
 * an unused-variable warning in every generated wrapper that has one.
 */
private fun CodeBlock.Builder.emitResult(
    function: ExportedFun,
    call: CodeBlock,
    serializers: SerializerCache,
) {
    if (function.returnType == UNIT) {
        addStatement("%L", call)
    } else {
        addStatement("val result = %L", call)
    }
    emitEncodedReturn(function, serializers)
}

/** Returns `result`, encoded, or zero bytes when there is nothing to encode. */
private fun CodeBlock.Builder.emitEncodedReturn(
    function: ExportedFun,
    serializers: SerializerCache,
) {
    if (function.returnType == UNIT) {
        addStatement("return ByteArray(0)")
    } else {
        // Cached for the same reason the element serializers are: a return type
        // that is not a plain builtin (`List<String>`, a nullable) resolves to
        // a constructor call, and inline it would run on every reply.
        addStatement(
            "return %M.encodeToByteArray(%L, result)",
            Runtime.Codec,
            serializers.nameFor(function.returnType),
        )
    }
}

/**
 * The functional interface the synthetic `$default` method is bound to.
 *
 * Its signature mirrors the synthetic's exactly: receiver first for an object
 * member, then the declared parameters, then one `Int` mask per 32 of them, then
 * the marker. That exactness is the point: it makes the call site a plain
 * interface call the JIT can inline, with primitives passed unboxed and no
 * `Array<Any?>` built per invocation, which is what `Method.invoke` costs.
 *
 * Reference-typed parameters are widened to nullable. At the JVM level that
 * changes nothing (the descriptor is the same), but an omitted argument is
 * passed as `null` here, and a non-null Kotlin parameter would reject it at
 * compile time.
 *
 * For a suspending target two things differ, and both come from the JVM shape of
 * a suspend function rather than from anything Kotlin shows in the source:
 *
 * - a raw `Continuation` sits between the declared parameters and the masks;
 * - the return type is `Any?`, because a suspend function returns `Object` at
 *   the JVM level: its declared type *or* the `COROUTINE_SUSPENDED` sentinel.
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
 * lambdas, so the result is an ordinary object with a direct call to the
 * target, not a reflective dispatch.
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

    // A suspend function returns Object at the JVM level whatever it declares,
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
