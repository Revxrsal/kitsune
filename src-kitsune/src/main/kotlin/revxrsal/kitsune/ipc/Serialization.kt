package revxrsal.kitsune.ipc

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/**
 * The codec both sides of the bridge encode with.
 *
 * CBOR rather than JSON because the payload never has to be read by a human and
 * crosses a JNI boundary as a `byte[]` either way — a JSON reply would mean an
 * extra UTF-8 encode on one side and a parse of text on the other, for nothing.
 *
 * `ignoreUnknownKeys` is what lets the Rust and Kotlin halves be versioned
 * independently: a host that has learned to send a field this build does not
 * know about should be ignored, not rejected.
 */
@OptIn(ExperimentalSerializationApi::class)
val KitsuneCbor: Cbor = Cbor {
    ignoreUnknownKeys = true
    alwaysUseByteString = true
}

/**
 * The trailing argument of every synthetic `$default` method the Kotlin compiler
 * emits.
 *
 * Its declared type is `kotlin.jvm.internal.DefaultConstructorMarker`, which is
 * internal to the stdlib and can never be non-null — the parameter exists only
 * to keep the synthetic overload's JVM signature distinct from the real one.
 * Generated code passes this constant so it does not have to name that type.
 */
@JvmField
val DEFAULT_CONSTRUCTOR_MARKER: Any? = null
