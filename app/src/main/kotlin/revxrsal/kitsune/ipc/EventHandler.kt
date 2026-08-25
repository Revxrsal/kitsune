package revxrsal.kitsune.ipc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.DeserializationStrategy

/**
 * The exported event types of this module and the listeners registered on them.
 *
 * Build it with `GeneratedEvents.handler()`; the KSP processor fills it from the
 * `@ExportEvent` and `@Listener` declarations.
 */
class EventHandler(private val scope: CoroutineScope = KitsuneScope) {

    private val deserializers = HashMap<String, DeserializationStrategy<*>>()
    private val blocking = HashMap<String, MutableList<(Any) -> Unit>>()
    private val suspending = HashMap<String, MutableList<suspend (Any) -> Unit>>()

    /** Every registered event id. */
    val events: Set<String> get() = deserializers.keys

    /**
     * Registers the payload type the host sends under [id].
     *
     * [T] is unused at runtime but is what ties the [deserializer] to the type
     * the matching [listener] call will receive, so the two cannot disagree
     * without the generated file failing to compile.
     */
    fun <T : Any> addEvent(id: String, deserializer: DeserializationStrategy<T>) {
        require(deserializers.put(id, deserializer) == null) {
            "An event is already registered under id '$id'"
        }
    }

    /**
     * Registers a callback for the event [id].
     *
     * Several listeners may share an id; they run in registration order, which
     * is declaration order in the generated file.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> listener(id: String, block: (T) -> Unit) {
        blocking.getOrPut(id) { mutableListOf() } += block as (Any) -> Unit
    }

    /**
     * Registers a `suspend` callback for the event [id].
     *
     * Unlike a plain [listener] it does not run during [dispatch]; it is launched
     * into this handler's scope and outlives the call. `dispatch` therefore
     * cannot report its failure, which is what the scope's exception handler is
     * for.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> suspendingListener(id: String, block: suspend (T) -> Unit) {
        suspending.getOrPut(id) { mutableListOf() } += block as suspend (Any) -> Unit
    }

    /**
     * Decodes [payload] as the event registered under [id], runs every plain
     * listener on it, and launches every suspending one.
     *
     * Decoding happens once even when several listeners are attached, and not at
     * all when none are — an event nothing listens for costs only the map lookup.
     *
     * Each suspending listener gets its own coroutine rather than sharing one:
     * they are independent, so neither should be able to delay the next or, under
     * [KitsuneScope]'s supervisor job, cancel it by failing.
     */
    fun dispatch(id: String, payload: ByteArray) {
        val deserializer = deserializers[id]
            ?: throw NoSuchElementException(
                "No exported event with id '$id'. Known: ${events.sorted()}"
            )
        val plain = blocking[id].orEmpty()
        val launched = suspending[id].orEmpty()
        if (plain.isEmpty() && launched.isEmpty()) return

        val event = KitsuneCbor.decodeFromByteArray(deserializer, payload) as Any
        for (handler in plain) handler(event)
        for (handler in launched) scope.launch { handler(event) }
    }
}
