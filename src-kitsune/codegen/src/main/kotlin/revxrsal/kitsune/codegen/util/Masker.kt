package revxrsal.kitsune.codegen.util

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.NameAllocator
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.joinToCode

/** `Int::class.javaPrimitiveType` — the type of each mask parameter. */
val INT_TYPE_BLOCK: CodeBlock = CodeBlock.of("%T::class.javaPrimitiveType", INT)

/**
 * `java.lang.Object::class.java`.
 *
 * Two places need it: the trailing marker parameter of every synthetic `$default`
 * method, and the return type of every suspend function's JVM signature.
 */
val JavaObjectType: CodeBlock = CodeBlock.of("java.lang.Object::class.java")

/**
 * The trailing marker parameter of a synthetic `$default` method.
 *
 * Its declared type is `kotlin.jvm.internal.DefaultConstructorMarker`, but that
 * class is internal to the stdlib and the erased descriptor is `Object`, which
 * is what the `MethodType` has to name.
 */
val DefaultConstructorMarkerType: CodeBlock = JavaObjectType

/**
 * Tracks the bitmask that Kotlin's synthetic `$default` methods take.
 *
 * The convention the compiler emits: bit *i* set means "argument *i* was **not**
 * supplied, substitute the declared default". So the mask starts at `-1` (every
 * bit set, every default applies) and a bit is cleared for each argument the
 * caller actually provided. One `Int` covers 32 parameters; past that the
 * compiler emits additional mask parameters, hence [maskCount].
 *
 * Bits are addressed by parameter index rather than by a cursor walked in step
 * with the parameter list. The mask is filled in by the decoder, which visits
 * parameters in whatever order the payload happens to list them — a cursor would
 * have to be driven by that order and would silently shift every subsequent bit
 * the moment the two walks diverged.
 */
class Masker(
    count: Int,
    nameAllocator: NameAllocator = NameAllocator(),
) {

    /** One mask per 32 parameters, rounded up. */
    val maskCount = if (count == 0) 0 else (count + 31) / 32

    val maskNames = Array(maskCount) { index -> nameAllocator.newName("mask$index") }

    /** The mask value that results when every defaulted parameter was supplied. */
    private val maskAllSetValues = Array(maskCount) { -1 }

    /** The mask holding parameter [index]'s bit. */
    fun maskNameOf(index: Int): String = maskNames[index / 32]

    /** Parameter [index]'s bit within [maskNameOf]. */
    fun bitOf(index: Int): Int = 1 shl (index % 32)

    /**
     * The masks as properties of the generated decoder, all defaults applying.
     *
     * Fields rather than locals of the wrapper: `deserialize` writes them and the
     * wrapper reads them afterwards, and a captured local `var` would be boxed
     * into an `IntRef` per call — one allocation per mask, on top of the decoder
     * itself.
     */
    fun fields(): List<PropertySpec> = maskNames.map { maskName ->
        PropertySpec.builder(maskName, INT)
            .addAnnotation(JvmFieldClass)
            .mutable(true)
            .initializer("-1")
            .build()
    }

    /**
     * Emits the statement clearing parameter [index]'s bit, and records the same
     * clearing in [maskAllSetValues] so [allArgumentsSupplied] stays in sync with
     * the code being generated.
     *
     * Called for mandatory parameters too, not just defaulted ones. Their bits
     * are what [requiredBits] then tests for a payload that omitted them, and
     * clearing them cannot disturb the call: the compiler only emits a
     * substitution branch for parameters that actually have a default, so the
     * synthetic method never reads the others' bits.
     */
    fun clearBit(code: CodeBlock.Builder, index: Int) {
        val maskIndex = index / 32
        val inverted = bitOf(index).inv()
        maskAllSetValues[maskIndex] = maskAllSetValues[maskIndex] and inverted
        code.addComment("\$mask = \$mask and (1 shl %L).inv()", index % 32)
        code.addStatement(
            "%1L = %1L and 0x%2L.toInt()",
            maskNames[maskIndex],
            Integer.toHexString(inverted),
        )
    }

    /**
     * The bits of [indices], one entry per mask that holds at least one of them.
     *
     * Grouped rather than returned per index so a check over many parameters
     * collapses to one `and` per mask instead of one per parameter.
     */
    fun requiredBits(indices: List<Int>): List<Pair<String, Int>> {
        val bits = IntArray(maskCount)
        for (index in indices) {
            bits[index / 32] = bits[index / 32] or bitOf(index)
        }
        return maskNames.withIndex()
            .filter { (index, _) -> bits[index] != 0 }
            .map { (index, maskName) -> maskName to bits[index] }
    }

    /**
     * The condition that holds when no default needs to be substituted.
     *
     * [prefix] qualifies the mask names — the masks live on the decoder object,
     * so the wrapper reads them through it while `deserialize` writes them bare.
     */
    fun allArgumentsSupplied(prefix: String = ""): CodeBlock =
        maskNames.withIndex().map { (index, maskName) ->
            CodeBlock.of("$prefix$maskName·== 0x${Integer.toHexString(maskAllSetValues[index])}.toInt()")
        }.joinToCode("·&& ")

    /** The masks, in order, as arguments to the synthetic method. See [allArgumentsSupplied] for [prefix]. */
    fun asArguments(prefix: String = ""): CodeBlock =
        maskNames.map { CodeBlock.of("%L%L", prefix, it) }.joinToCode(", ")
}
