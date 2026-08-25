package revxrsal.kitsune.codegen.util

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE
import com.squareup.kotlinpoet.CHAR
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.DelicateKotlinPoetApi
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.NOTHING
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName

// Adapted from Moshi's codegen, licensed under Apache 2.0.
//
// https://github.com/square/moshi/blob/master/moshi-kotlin-codegen/src/main/java/com/squareup/moshi/kotlin/codegen/api/kotlintypes.kt

internal fun TypeName.findRawType(): ClassName? = when (this) {
    is ClassName -> this
    is ParameterizedTypeName -> rawType
    is LambdaTypeName -> {
        var count = parameters.size
        if (receiver != null) count++
        val functionSimpleName = if (count >= 23) "FunctionN" else "Function$count"
        ClassName("kotlin.jvm.functions", functionSimpleName)
    }

    else -> null
}

internal fun TypeName.rawType(): ClassName =
    findRawType() ?: throw IllegalArgumentException("Cannot get raw type from $this")

/** Whether this is one of the JVM primitive types in their non-null form. */
fun TypeName.isPrimitive(): Boolean = when (this) {
    BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE -> true
    else -> false
}

/**
 * The expression that yields this type's `java.lang.Class`, as it must appear in
 * a `getDeclaredMethod` call.
 *
 * The distinction that matters here is `Int::class.javaPrimitiveType` versus
 * `Int::class.javaObjectType`. A Kotlin `fun f(a: Int = 1)` compiles to a
 * synthetic `f$default(int, int, Object)`, so a `MethodType` built with
 * `Integer::class.java` matches nothing and `findStatic` throws
 * `NoSuchMethodException` at runtime instead of failing the build. Nullable
 * `Int?` on the other hand really is boxed, hence the split on
 * [TypeName.isNullable].
 */
@OptIn(DelicateKotlinPoetApi::class)
internal fun TypeName.asTypeBlock(): CodeBlock {
    if (annotations.isNotEmpty()) {
        return copy(annotations = emptyList()).asTypeBlock()
    }
    when (this) {
        is ParameterizedTypeName -> {
            return if (rawType == ARRAY) {
                val componentType = typeArguments[0]
                if (componentType is ParameterizedTypeName) {
                    // A "generic" array erases to its component's raw type:
                    // java.lang.reflect.Array.newInstance(<raw-type>, 0).javaClass
                    CodeBlock.of(
                        "%T.newInstance(%L, 0).javaClass",
                        Array::class.java.asClassName(),
                        componentType.rawType.asTypeBlock(),
                    )
                } else {
                    CodeBlock.of("%T::class.java", copy(nullable = false))
                }
            } else {
                rawType.asTypeBlock()
            }
        }

        is TypeVariableName -> return (bounds.firstOrNull() ?: ANY).asTypeBlock()

        is LambdaTypeName -> return rawType().asTypeBlock()

        is ClassName -> return when (copy(nullable = false)) {
            BOOLEAN, CHAR, BYTE, SHORT, INT, FLOAT, LONG, DOUBLE ->
                if (isNullable) {
                    CodeBlock.of("%T::class.javaObjectType", copy(nullable = false))
                } else {
                    CodeBlock.of("%T::class.javaPrimitiveType", this)
                }

            UNIT, Void::class.asTypeName(), NOTHING ->
                throw IllegalStateException("Parameter with void, Unit, or Nothing type is illegal")

            else -> CodeBlock.of("%T::class.java", copy(nullable = false))
        }

        else -> throw UnsupportedOperationException(
            "Parameter with type '${javaClass.simpleName}' is illegal. Only classes, " +
                "parameterized types, or type variables are allowed."
        )
    }
}

/**
 * The expression yielding this *return* type's `java.lang.Class`, as a
 * `MethodType` needs it.
 *
 * `Unit` is the reason this is separate from [asTypeBlock], which rejects it: a
 * Kotlin function returning `Unit` compiles to a `void` method, so the descriptor
 * the synthetic method was compiled with has `void` in the return position and
 * nothing else will match it.
 */
fun TypeName.asReturnTypeBlock(): CodeBlock =
    if (this == UNIT) CodeBlock.of("java.lang.Void.TYPE") else asTypeBlock()

/**
 * The "absent" value for a type, used when calling the synthetic `$default`
 * method for a parameter the caller left out.
 *
 * The value is never observed: the mask tells the synthetic method to overwrite
 * it with the real default. It exists only because the synthetic's primitive
 * parameters stay unboxed on the functional interface bound to it, so there is
 * no `null` to pass in an `int` slot.
 */
fun TypeName.emptyValue(): String = when (this) {
    BOOLEAN -> "false"
    BYTE, SHORT, INT -> "0"
    LONG -> "0L"
    DOUBLE -> "0.0"
    FLOAT -> "0.0f"
    CHAR -> "'0'"
    else -> "null"
}
