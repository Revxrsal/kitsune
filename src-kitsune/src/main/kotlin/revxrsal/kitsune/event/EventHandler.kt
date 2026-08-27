package revxrsal.kitsune.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import revxrsal.kitsune.coroutines.KitsuneScope
import revxrsal.kitsune.ipc.KitsuneCbor

/**
 * The exported event types of this module and the listeners registered on them.
 *
 * Build it with `GeneratedEvents.handler()`; the KSP processor fills it from the
 * `@ExportEvent` and `@Listener` declarations.
 *
 * Everything is a flat array indexed by the event's **ordinal**, which is what
 * the payload carries in its first two bytes, so dispatch costs a bounds check
 * and two loads, and no part of the frame has to be decoded to find out where it
 * goes. [ids], [serializers], [listeners] and [suspendingListeners] are parallel:
 * index *i* is the same event in all four, which the generator guarantees by
 * building them in one pass.
 *
 * [classNames] is the exception, and is sorted by class name so that the
 * `reified` [dispatch] can search it; [ordinalsByClassName] carries the ordinals
 * it would otherwise have lost. Both come sorted from the generator, which knows
 * the whole table at build time, so nothing is arranged at runtime.
 *
 * [ids] is here for diagnostics, and for [ordinalOf]. Nothing dispatches on it.
 */
class EventHandler(
    private val ids: Array<String>,
    private val classNames: Array<String>,
    private val ordinalsByClassName: IntArray,
    private val serializers: Array<KSerializer<*>>,
    private val listeners: Array<Array<(Any) -> Unit>>,
    private val suspendingListeners: Array<Array<suspend (Any) -> Unit>>,
    private val scope: CoroutineScope = KitsuneScope,
) {

    init {
        require(
            ids.size == serializers.size &&
                    ids.size == listeners.size &&
                    ids.size == suspendingListeners.size &&
                    ids.size == classNames.size &&
                    ids.size == ordinalsByClassName.size
        ) {
            "Event tables disagree on how many events there are: ${ids.size} ids, " +
                    "${serializers.size} serializers, ${listeners.size} listener rows, " +
                    "${suspendingListeners.size} suspending listener rows, " +
                    "${classNames.size} class names, ${ordinalsByClassName.size} class ordinals"
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

    /**
     * Raises [event] under the ordinal its type was exported at, forwarding it
     * outward and then to the local listeners, as [dispatch] does.
     *
     * `reified` buys exactly one thing here (the class name, without the caller
     * having to name the type twice), so that is all this function does with it.
     * The work is [dispatchNamed]'s, which is what lets the tables stay private.
     */
    inline fun <reified T : Any> dispatch(event: T) {
        dispatchNamed(T::class.java.name, event)
    }

    /**
     * The body of the `reified` [dispatch]. `@PublishedApi` because an inline
     * function's body is compiled into its callers, so what it touches has to be
     * reachable from them; `internal` so that is the only thing they can reach.
     */
    @PublishedApi
    @OptIn(ExperimentalSerializationApi::class)
    internal fun dispatchNamed(className: String, event: Any) {
        val found = classNames.binarySearch(className)
        if (found < 0) {
            throw NoSuchElementException(
                "$className is not an exported event. Known: ${ids.size} events, ${ids.asList()}"
            )
        }
        val ordinal = ordinalsByClassName[found]

        @Suppress("UNCHECKED_CAST")
        val serializer = serializers[ordinal] as KSerializer<Any>
        NativeEventBridge.kotlinEmittedEvent(
            ordinal,
            KitsuneCbor.encodeToByteArray(serializer, event),
        )

        val plain = listeners[ordinal]
        val launched = suspendingListeners[ordinal]
        for (handler in plain) handler(event)
        for (handler in launched) scope.launch { handler(event) }
    }

    /**
     * Decodes [payload] as the event registered at [ordinal], runs every plain
     * listener on it, and launches every suspending one.
     *
     * Decoding happens once even when several listeners are attached, and not at
     * all when none are: an event nothing listens for costs only the bounds
     * check.
     *
     * Each suspending listener gets its own coroutine rather than sharing one:
     * they are independent, so neither should be able to delay the next or, under
     * [KitsuneScope]'s supervisor job, cancel it by failing.
     *
     * An event this side raised is forwarded outward first, before any local
     * listener runs. The forward is what the other half of the app is waiting on,
     * and a listener here (which may be slow, and may throw) has no business
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

        val event = KitsuneCbor.decodeFromByteArray(serializers[ordinal], payload) as Any
        for (handler in plain) handler(event)
        for (handler in launched) scope.launch { handler(event) }
    }
}

enum class EventSource {
    KOTLIN,
    JAVASCRIPT
}
