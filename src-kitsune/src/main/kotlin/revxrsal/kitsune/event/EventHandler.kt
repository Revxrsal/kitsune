package revxrsal.kitsune.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import revxrsal.kitsune.ipc.KitsuneCbor
import revxrsal.kitsune.coroutines.KitsuneScope

/**
 * The exported event types of this module and the listeners registered on them.
 *
 * Build it with `GeneratedEvents.handler()`; the KSP processor fills it from the
 * `@ExportEvent` and `@Listener` declarations.
 *
 * Everything is a flat array indexed by the event's **ordinal**, which is what
 * the payload carries in its first two bytes — so dispatch costs a bounds check
 * and two loads, and no part of the frame has to be decoded to find out where it
 * goes. The four arrays are parallel: index *i* is the same event in all of them,
 * which the generator guarantees by building them in one pass.
 *
 * [ids] is here for diagnostics, and for [ordinalOf]. Nothing dispatches on it.
 */
class EventHandler(
    private val ids: Array<String>,
    val idsByClassName: Array<String>,
    private val deserializers: Array<DeserializationStrategy<*>>,
    private val listeners: Array<Array<(Any) -> Unit>>,
    private val suspendingListeners: Array<Array<suspend (Any) -> Unit>>,
    private val scope: CoroutineScope = KitsuneScope,
) {

    init {
        require(
            ids.size == deserializers.size &&
                    ids.size == listeners.size &&
                    ids.size == suspendingListeners.size
        ) {
            "Event tables disagree on how many events there are: ${ids.size} ids, " +
                    "${deserializers.size} deserializers, ${listeners.size} listener rows, " +
                    "${suspendingListeners.size} suspending listener rows"
        }
    }

    /** Every registered event id, in ordinal order. */
    internal val events: List<String> get() = ids.asList()

    /**
     * The ordinal [id] travels under, or `-1`.
     *
     * A binary search rather than a map: the ids are sorted, because sorting
     * them is how the ordinals were handed out in the first place. For a table
     * this size the search beats hashing, and it costs no extra structure at all.
     */
    internal fun ordinalOf(id: String): Int = ids.binarySearch(id).let { if (it < 0) -1 else it }

    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T> dispatch(
        event: T,
        serializer: SerializationStrategy<T> = serializer<T>(),
    ) {
        val ordinal = idsByClassName.binarySearch(T::class.java.name).let { if (it < 0) -1 else it }
        val payload = KitsuneCbor.encodeToByteArray(serializer, event)
        dispatch(ordinal, payload, EventSource.KOTLIN)
    }

    /**
     * Decodes [payload] as the event registered at [ordinal], runs every plain
     * listener on it, and launches every suspending one.
     *
     * Decoding happens once even when several listeners are attached, and not at
     * all when none are — an event nothing listens for costs only the bounds
     * check.
     *
     * Each suspending listener gets its own coroutine rather than sharing one:
     * they are independent, so neither should be able to delay the next or, under
     * [KitsuneScope]'s supervisor job, cancel it by failing.
     *
     * An event this side raised is forwarded outward first, before any local
     * listener runs. The forward is what the other half of the app is waiting on,
     * and a listener here — which may be slow, and may throw — has no business
     * sitting in front of it.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun dispatch(ordinal: Int, payload: ByteArray, source: EventSource = EventSource.KOTLIN) {
        if (ordinal < 0 || ordinal >= ids.size) {
            throw NoSuchElementException(
                "No exported event at ordinal $ordinal. Known: ${ids.size} events, ${ids.asList()}"
            )
        }
        if (source == EventSource.KOTLIN) {
            NativeEventBridge.kotlinEmittedEvent(ordinal, payload)
        }

        val plain = listeners[ordinal]
        val launched = suspendingListeners[ordinal]
        if (plain.isEmpty() && launched.isEmpty()) return

        val event = KitsuneCbor.decodeFromByteArray(deserializers[ordinal], payload) as Any
        for (handler in plain) handler(event)
        for (handler in launched) scope.launch { handler(event) }
    }
}

enum class EventSource {
    KOTLIN,
    JAVASCRIPT
}
