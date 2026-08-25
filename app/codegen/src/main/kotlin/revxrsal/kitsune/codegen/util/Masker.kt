package revxrsal.kitsune.codegen.util

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.NameAllocator
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

    private var maskIndex = 0
    private var maskNameIndex = 0

    /** Advances to the next parameter position, rolling over every 32 bits. */
    fun advance() {
        maskIndex++
        if (maskIndex == 32) {
            maskIndex = 0
            maskNameIndex++
        }
    }

    /** Declares the mask locals, all defaults applying. */
    fun declare(code: CodeBlock.Builder) {
        for (maskName in maskNames) {
            code.addStatement("var %L = -1", maskName)
        }
    }

    /**
     * Emits the statement clearing the current parameter's bit, and records the
     * same clearing in [maskAllSetValues] so [allArgumentsSupplied] stays in
     * sync with the code being generated.
     */
    fun clearCurrentBit(code: CodeBlock.Builder) {
        val inverted = (1 shl maskIndex).inv()
        maskAllSetValues[maskNameIndex] = maskAllSetValues[maskNameIndex] and inverted
        code.addComment("\$mask = \$mask and (1 shl %L).inv()", maskIndex)
        code.addStatement(
            "%1L = %1L and 0x%2L.toInt()",
            maskNames[maskNameIndex],
            Integer.toHexString(inverted),
        )
    }

    /** The condition that holds when no default needs to be substituted. */
    fun allArgumentsSupplied(): CodeBlock = maskNames.withIndex().map { (index, maskName) ->
        CodeBlock.of("$maskName·== 0x${Integer.toHexString(maskAllSetValues[index])}.toInt()")
    }.joinToCode("·&& ")

    /** The mask locals, in order, as arguments to the synthetic method. */
    fun asArguments(): CodeBlock = maskNames.map { CodeBlock.of("%L", it) }.joinToCode(", ")
}
