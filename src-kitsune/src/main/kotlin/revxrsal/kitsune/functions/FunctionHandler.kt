package revxrsal.kitsune.functions

import kotlinx.coroutines.*
import revxrsal.kitsune.coroutines.KitsuneScope

/**
 * One exported function, in whichever of the two shapes it was declared.
 *
 * The split is not cosmetic. A `suspend` export cannot be called from the host's
 * synchronous JNI thread without either blocking it or launching a coroutine,
 * and which of those is right is the caller's decision, so the distinction has
 * to survive into the registry rather than being erased by wrapping everything
 * in `runBlocking` at generation time.
 */
sealed interface ExportedFunction {

    /** A plain function; it returns on the thread that called it. */
    fun interface Blocking : ExportedFunction {
        operator fun invoke(request: ByteArray): ByteArray
    }

    /** A `suspend` function; its result arrives when the coroutine completes. */
    fun interface Suspending : ExportedFunction {
        suspend operator fun invoke(request: ByteArray): ByteArray
    }
}

/**
 * The exported functions of this module, indexed by the **ordinal** the host
 * dispatches with, the number the payload carries in its first two bytes.
 *
 * Construct it from `GeneratedFunctions.handler()` rather than by hand: the
 * arrays it wraps are written by the KSP processor from the `@ExportFunction`
 * declarations, and a hand-built pair silently diverges. [exportedNames] is
 * parallel to [functions] and exists only so a bad ordinal can be reported in
 * terms a person recognises; nothing dispatches on it.
 */
class FunctionHandler(
    private val functions: Array<ExportedFunction>,
    private val exportedNames: Array<String>,
    private val scope: CoroutineScope = KitsuneScope,
) {

    init {
        require(functions.size == exportedNames.size) {
            "Function tables disagree on how many exports there are: " +
                    "${functions.size} functions, ${exportedNames.size} names"
        }
    }

    /** Every exported name, in ordinal order, for diagnostics. */
    val names: List<String> get() = exportedNames.asList()

    /**
     * The ordinal [name] is exported under, or `-1`.
     *
     * A binary search rather than a map: the names are sorted, because sorting
     * them is how the ordinals were handed out in the first place.
     */
    fun ordinalOf(name: String): Int =
        exportedNames.binarySearch(name).let { if (it < 0) -1 else it }

    operator fun contains(name: String): Boolean = ordinalOf(name) >= 0

    /** Whether the export at [ordinal] was declared `suspend`, and so cannot go through [call]. */
    fun isSuspending(ordinal: Int): Boolean = resolve(ordinal) is ExportedFunction.Suspending

    /**
     * Invokes a non-suspending export on the calling thread and returns its
     * encoded result. A function returning `Unit` returns zero bytes.
     *
     * A suspending export is rejected rather than quietly blocked: the host
     * thread calling in is the one the JVM was entered on, and stalling it on a
     * coroutine that may be waiting for the network is a decision the caller has
     * to make knowingly. [callBlocking] and [launchCall] are the two ways to make it.
     */
    fun call(ordinal: Int, request: ByteArray): ByteArray = when (val function = resolve(ordinal)) {
        is ExportedFunction.Blocking -> function(request)
        is ExportedFunction.Suspending -> throw IllegalStateException(
            "'${exportedNames[ordinal]}' is a suspend function; use callBlocking() or " +
                    "launchCall() instead of call()."
        )
    }

    /**
     * Invokes any export, blocking the calling thread until it completes.
     *
     * This is the adapter for today's bridge, whose single JNI method is
     * synchronous and has nowhere to deliver a later result. It is not a
     * substitute for [launchCall] once the protocol grows a reply channel.
     */
    fun callBlocking(ordinal: Int, request: ByteArray): ByteArray =
        when (val function = resolve(ordinal)) {
            is ExportedFunction.Blocking -> function(request)
            // Deliberately not `runBlocking(scope.coroutineContext)`: that would
            // dispatch the body onto Dispatchers.Default and block this thread
            // waiting for it, spending a thread to save nothing. Plain
            // runBlocking runs it on this thread's event loop, and any export
            // that needs a different dispatcher asks for one itself.
            is ExportedFunction.Suspending -> runBlocking { function(request) }
        }

    /**
     * Launches any export in this handler's scope and hands the outcome to
     * [onComplete].
     *
     * [onComplete] runs on whichever thread the coroutine finished on, which is
     * not the caller's, so anything it touches has to be safe for that. A failure
     * is delivered as a failed [Result] rather than thrown, so a host callback
     * sees both outcomes through one path.
     */
    private val COMPLETED: Job = Job().apply { complete() }

    private inline fun deliver(onComplete: (Result<ByteArray>) -> Unit, r: Result<ByteArray>) {
        try {
            onComplete(r)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun launchCall(
        ordinal: Int,
        request: ByteArray,
        onComplete: (Result<ByteArray>) -> Unit,
    ): Job {
        // Resolution failure is delivered, never thrown at the caller.
        val f = try {
            resolve(ordinal)
        } catch (t: Throwable) {
            deliver(onComplete, Result.failure(t))
            return COMPLETED
        }

        // FAST PATH: no coroutine, no dispatch.
        if (f is ExportedFunction.Blocking) {
            deliver(onComplete, runCatching { f(request) })
            return COMPLETED
        }

        // Suspending: run inline until the first real suspension point.
        f as ExportedFunction.Suspending
        val d = scope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                Result.success(f(request))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
        d.invokeOnCompletion { cause ->
            deliver(onComplete, if (cause != null) Result.failure(cause) else d.getCompleted())
        }
        return d
    }

    /**
     * An out-of-range ordinal is an error rather than an empty reply: the host
     * and this module are built from the same source tree, so it means they have
     * drifted, and the caller is owed that rather than a plausible-looking empty
     * response.
     *
     * The check is explicit rather than left to the array, because
     * `ArrayIndexOutOfBoundsException` crossing the bridge says nothing about
     * what went wrong, and drift is exactly the case where the message has to
     * carry the diagnosis.
     */
    private fun resolve(ordinal: Int): ExportedFunction {
        if (ordinal < 0 || ordinal >= functions.size) {
            throw NoSuchElementException(
                "No exported function at ordinal $ordinal. Known: ${functions.size} functions, " +
                        "${exportedNames.asList()}"
            )
        }
        return functions[ordinal]
    }
}
