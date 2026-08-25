package revxrsal.kitsune.codegen.functions

import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.ksp.toTypeName
import revxrsal.kitsune.codegen.util.asTypeBlock
import revxrsal.kitsune.codegen.util.emptyValue
import revxrsal.kitsune.codegen.util.isPrimitive

/**
 * One parameter of an exported function or listener, in the two forms generated
 * code needs it: a property on the `@Serializable` argument holder, and an
 * expression that reads it back out.
 */
class ExportedParameter(parameter: KSValueParameter) {

    val name = parameter.name!!.asString()

    val type = parameter.type.resolve()

    val hasDefault = parameter.hasDefault

    val isVararg = parameter.isVararg

    /** Whether the *declared* parameter accepts null. */
    val isNullable = type.isMarkedNullable

    /** The declared type, as written in the source. */
    val declaredType = type.toTypeName()

    val isPrimitive = declaredType.isPrimitive()

    /**
     * The type as it appears on the argument holder.
     *
     * Defaulted parameters are widened to nullable — including primitives, which
     * kono left unboxed. That widening *is* the presence tracking: kono had to
     * ship a separate `passedParameters` list alongside the payload because a
     * JSON object cannot distinguish an absent `Int` field from `0`. Boxing it
     * makes absence representable in the decoded object itself, so the mask is
     * computed from the holder and nothing extra travels over the bridge.
     *
     * The one thing this cannot express is a parameter that is *both* nullable
     * and defaulted: an explicit null and an omitted argument decode
     * identically, and the default wins. Declaring `x: Int? = null` is therefore
     * fine (both readings agree); `x: Int? = 5` is not, and is rejected by
     * [checkExportable].
     */
    val holderType = if (hasDefault) declaredType.copy(nullable = true) else declaredType

    /**
     * The type this parameter takes on the synthetic method's functional
     * interface.
     *
     * Reference types are widened to nullable so an omitted argument can be
     * passed as `null`; primitives are left alone, which is what keeps the call
     * unboxed.
     */
    val syntheticType = if (isPrimitive) declaredType else declaredType.copy(nullable = true)

    /** `Foo::class.java` / `Int::class.javaPrimitiveType`, for the `MethodType`. */
    val typeBlock: CodeBlock by lazy(LazyThreadSafetyMode.NONE) { declaredType.asTypeBlock() }
}

/**
 * Reads the parameter for a direct, named call to the real function.
 *
 * Only reached when every defaulted parameter was supplied, so a null in a
 * non-null slot is a protocol violation by the host rather than a missing
 * argument — hence the error rather than a substitution.
 */
fun ExportedParameter.directAccess(holder: String): CodeBlock = buildCodeBlock {
    add("%L.%L", holder, name)
    if (holderType.isNullable && !isNullable) {
        add(" ?: error(%S)", "null was provided for non-null parameter '$name'")
    }
}

/**
 * Reads the parameter for a call through the synthetic `$default` method.
 *
 * Absent primitives become their zero value. The mask makes the callee discard
 * it, but the interface parameter is an unboxed `Int`, so there is no null to
 * pass in the first place.
 */
fun ExportedParameter.syntheticAccess(holder: String): CodeBlock = buildCodeBlock {
    add("%L.%L", holder, name)
    if (holderType.isNullable && isPrimitive) {
        add(" ?: %L", declaredType.emptyValue())
    }
}

/** The property this parameter contributes to the generated argument holder. */
fun ExportedParameter.toHolderParameter(): ParameterSpec {
    val builder = ParameterSpec.builder(name, holderType)
    if (hasDefault) builder.defaultValue("null")
    return builder.build()
}
