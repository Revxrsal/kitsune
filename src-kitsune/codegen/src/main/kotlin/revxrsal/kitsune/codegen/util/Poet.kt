package revxrsal.kitsune.codegen.util

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName

/** `kotlinx.serialization.serializer<T>()`, resolved at compile time by the plugin. */
val SerializerFunction = MemberName("kotlinx.serialization", "serializer")

/**
 * The `kotlinx.serialization` API the generated argument decoders are written
 * against.
 *
 * A decoder is hand-written rather than left to `@Serializable` because the
 * plugin-generated one cannot answer the question the wrapper actually has to
 * ask. It decodes a *value* per property, and a property that was absent and a
 * property that was explicitly null both arrive as `null`, which is precisely
 * the distinction the default-argument mask is built from. `decodeElementIndex`
 * visits only the keys the payload really carried, so writing the loop out by
 * hand recovers presence for free, during the decode that was happening anyway.
 *
 * Most of this surface is still `@ExperimentalSerializationApi`, hence the
 * file-level opt-in on the generated file. It has been stable for years and the
 * alternative is shipping a wire format that cannot express an explicit null.
 */
val ExperimentalSerializationApiClass =
    ClassName("kotlinx.serialization", "ExperimentalSerializationApi")
val DeserializerStrategyClass = ClassName("kotlinx.serialization", "DeserializationStrategy")
val KSerializerClass = ClassName("kotlinx.serialization", "KSerializer")
val SerializationExceptionClass = ClassName("kotlinx.serialization", "SerializationException")

val SerialDescriptorClass = ClassName("kotlinx.serialization.descriptors", "SerialDescriptor")
val BuildClassSerialDescriptor =
    MemberName("kotlinx.serialization.descriptors", "buildClassSerialDescriptor")
val DescriptorElement = MemberName("kotlinx.serialization.descriptors", "element")

val DecoderClass = ClassName("kotlinx.serialization.encoding", "Decoder")
val CompositeDecoderClass = ClassName("kotlinx.serialization.encoding", "CompositeDecoder")
val DecodeStructure = MemberName("kotlinx.serialization.encoding", "decodeStructure")

/**
 * `@JvmField`, put on every property of a generated decoder.
 *
 * They are written by `deserialize` and read by the wrapper right after, so a
 * getter pair around each one is pure overhead. Nothing outside the generated
 * file can see them.
 */
val JvmFieldClass = ClassName("kotlin.jvm", "JvmField")

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
 * `java.lang.invoke` types used to bind the synthetic `$default` methods.
 *
 * Named rather than referenced as `KClass`, so the generated file imports them
 * under their own names instead of KotlinPoet spelling out `java.lang.invoke.…`
 * at every use site.
 */
val MethodHandlesClass = ClassName("java.lang.invoke", "MethodHandles")
val MethodTypeClass = ClassName("java.lang.invoke", "MethodType")
val LambdaMetafactoryClass = ClassName("java.lang.invoke", "LambdaMetafactory")
