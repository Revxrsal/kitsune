package revxrsal.kitsune.codegen.util

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

/** `@kotlinx.serialization.Serializable`, put on every generated argument holder. */
val SerializableClass = ClassName("kotlinx.serialization", "Serializable")
val SERIALIZABLE: AnnotationSpec = AnnotationSpec.builder(SerializableClass).build()

/** `kotlinx.serialization.serializer<T>()`, resolved at compile time by the plugin. */
val SerializerFunction = MemberName("kotlinx.serialization", "serializer")

/**
 * Emits a `//` comment.
 *
 * The non-breaking spaces are deliberate: KotlinPoet wraps on ordinary spaces
 * and would happily fold a long comment onto a following line, where the `//`
 * no longer precedes it and the generated file stops compiling.
 */
fun CodeBlock.Builder.addComment(format: String, vararg args: Any) {
    add("//·${format.replace(' ', '·')}\n", *args)
}

/**
 * Declares [parameters] as both constructor parameters and `val` properties.
 *
 * KotlinPoet keeps the two lists separate; a primary constructor alone produces
 * a class whose "properties" are unreadable constructor locals.
 */
fun TypeSpec.Builder.primaryConstructor(parameters: List<ParameterSpec>): TypeSpec.Builder {
    val properties = parameters.map {
        PropertySpec.builder(it.name, it.type).initializer(it.name).build()
    }
    return primaryConstructor(FunSpec.constructorBuilder().addParameters(parameters).build())
        .addProperties(properties)
}

/**
 * `java.lang.invoke` types used to bind the synthetic `$default` methods.
 *
 * Named rather than referenced as `KClass`, so the generated file imports them
 * under their own names instead of KotlinPoet spelling out `java.lang.invoke.…`
 * at every use site.
 */
val MethodHandlesClass = ClassName("java.lang.invoke", "MethodHandles")
val MethodTypeClass = ClassName("java.lang.invoke", "MethodType")
val LambdaMetafactoryClass = ClassName("java.lang.invoke", "LambdaMetafactory")
