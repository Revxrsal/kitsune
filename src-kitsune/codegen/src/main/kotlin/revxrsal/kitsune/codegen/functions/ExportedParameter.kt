package revxrsal.kitsune.codegen.functions

import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.ksp.toTypeName
import revxrsal.kitsune.codegen.util.DescriptorElement
import revxrsal.kitsune.codegen.util.JvmFieldClass
import revxrsal.kitsune.codegen.util.SerializationExceptionClass
import revxrsal.kitsune.codegen.util.asTypeBlock
import revxrsal.kitsune.codegen.util.emptyValue
import revxrsal.kitsune.codegen.util.isPrimitive

/**
 * One parameter of an exported function or listener, in the three forms
 * generated code needs it: an element of the argument descriptor, a field on the
 * decoder that reads it, and an expression that reads it back out.
 */
class ExportedParameter(parameter: KSValueParameter) {

    /** The declaration itself, kept so diagnostics can point at the parameter. */
    val declaration = parameter

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
     * The type of the decoder field this parameter is read into.
     *
     * Always nullable, whatever was declared: the field exists before the payload
     * has been looked at, and `null` is the only starting value every type has.
     *
     * The null it starts at carries no meaning of its own, which is the point.
     * Presence is tracked separately, by the mask: `deserialize` clears this
     * parameter's bit when the decoder actually visits its key. That is what lets
     * a parameter be *both* nullable and defaulted: an omitted argument leaves
     * the bit set and the declared default applies, while an explicit null clears
     * it and the null is passed through. An earlier design widened the type on a
     * `@Serializable` holder instead and could not tell those two apart, because
     * the plugin-generated decoder reports a value per property and never says
     * which keys it saw.
     */
    val fieldType = declaredType.copy(nullable = true)

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
 * Reads this parameter out of the payload, into the decoder's field.
 *
 * A non-null parameter rejects an explicit null here, at the point of decoding,
 * rather than at either call site. The two call sites disagree about what a null
 * field means (the direct call treats it as a value, the synthetic one as an
 * omitted argument), and only the decoder is in a position to know it was a key
 * the host actually sent. Guarding here also makes the field's null unambiguous
 * for everything downstream: for a non-null parameter it can only mean absent.
 */
fun ExportedParameter.readElement(
    index: Int,
    function: String,
    serializer: String,
): CodeBlock = buildCodeBlock {
    add("decodeNullableSerializableElement(descriptor,·%L,·%L)", index, serializer)
    if (!isNullable) {
        add(
            "·?:·throw·%T(%S)",
            SerializationExceptionClass,
            "null was provided for non-null parameter '$name' of '$function'.",
        )
    }
}

/**
 * Reads the parameter for a direct, named call to the real function.
 *
 * The elvis is unreachable ([readElement] has already rejected an explicit
 * null, and the mask has confirmed the key was present), but the field is
 * nullable and the compiler is owed something. `error` rather than `!!` so that
 * if the reasoning above ever stops holding, the failure says which parameter.
 */
fun ExportedParameter.directAccess(decoder: String, field: String): CodeBlock = buildCodeBlock {
    add("%L.%L", decoder, field)
    if (!isNullable) {
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
fun ExportedParameter.syntheticAccess(decoder: String, field: String): CodeBlock = buildCodeBlock {
    add("%L.%L", decoder, field)
    if (isPrimitive) {
        add(" ?: %L", declaredType.emptyValue())
    }
}

/**
 * The element this parameter contributes to the argument descriptor.
 *
 * Named after the *parameter*, not after [field]: this is the key the host puts
 * on the wire, and the field is only what the decoder happens to call its slot.
 *
 * Every element is optional. Absence is not an error the format should raise
 * here: which parameters may be left out is a property of the Kotlin
 * declaration, and the generated wrapper checks it against the mask, with a
 * message that can name the function and every field that was missing at once.
 */
fun ExportedParameter.toDescriptorElement(): CodeBlock =
    CodeBlock.of("%M<%T>(%S,·isOptional·=·true)", DescriptorElement, fieldType, name)

/** The decoder field this parameter is read into. */
fun ExportedParameter.toDecoderField(field: String): PropertySpec =
    PropertySpec.builder(field, fieldType)
        .addAnnotation(JvmFieldClass)
        .mutable(true)
        .initializer("null")
        .build()
