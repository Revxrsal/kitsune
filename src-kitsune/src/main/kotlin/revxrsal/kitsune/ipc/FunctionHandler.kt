package revxrsal.kitsune.ipc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * One exported function, in whichever of the two shapes it was declared.
 *
 * The split is not cosmetic. A `suspend` export cannot be called from the host's
 * synchronous JNI thread without either blocking it or launching a coroutine,
 * and which of those is right is the caller's decision — so the distinction has
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
 * The exported functions of this module, keyed by the name the host dispatches
 * with.
 *
 * Construct it from `GeneratedFunctions.handler()` rather than by hand — the map
 * it wraps is written by the KSP processor from the `@ExportFunction`
 * declarations, and a hand-built one silently diverges.
 */
class FunctionHandler(
    private val functions: Map<String, ExportedFunction>,
    private val scope: CoroutineScope = KitsuneScope,
) {

    /** Every exported name, for diagnostics and for the host to validate against. */
    val names: Set<String> get() = functions.keys

    operator fun contains(name: String): Boolean = name in functions

    /** Whether [name] was declared `suspend`, and so cannot go through [call]. */
    fun isSuspending(name: String): Boolean = resolve(name) is ExportedFunction.Suspending

    /**
     * Invokes a non-suspending export on the calling thread and returns its
     * encoded result. A function returning `Unit` returns zero bytes.
     *
     * A suspending export is rejected rather than quietly blocked: the host
     * thread calling in is the one the JVM was entered on, and stalling it on a
     * coroutine that may be waiting for the network is a decision the caller has
     * to make knowingly. [callBlocking] and [launchCall] are the two ways to make it.
     */
    fun call(name: String, request: ByteArray): ByteArray = when (val function = resolve(name)) {
        is ExportedFunction.Blocking -> function(request)
        is ExportedFunction.Suspending -> throw IllegalStateException(
            "'$name' is a suspend function; use callBlocking() or launchCall() instead of call()."
        )
    }

    /**
     * Invokes any export, blocking the calling thread until it completes.
     *
     * This is the adapter for today's bridge, whose single JNI method is
     * synchronous and has nowhere to deliver a later result. It is not a
     * substitute for [launchCall] once the protocol grows a reply channel.
     */
    fun callBlocking(name: String, request: ByteArray): ByteArray =
        when (val function = resolve(name)) {
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
     * not the caller's — anything it touches has to be safe for that. A failure
     * is delivered as a failed [Result] rather than thrown, so a host callback
     * sees both outcomes through one path.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun launchCall(
        name: String,
        request: ByteArray,
        onComplete: (Result<ByteArray>) -> Unit,
    ): Job {
        val d = scope.async {
            try {
                val f = resolve(name)
                Result.success(
                    when (f) {
                    is ExportedFunction.Blocking -> withContext(Dispatchers.IO) { f(request) }
                    is ExportedFunction.Suspending -> f(request)
                })
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
        d.invokeOnCompletion { cause ->
            val r = if (cause != null)
                Result.failure(cause)
            else
                d.getCompleted()
            try {
                onComplete(r)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
        return d
    }

    /**
     * An unknown name is an error rather than an empty reply: the host and this
     * module are built from the same source tree, so it means they have drifted,
     * and the caller is owed that rather than a plausible-looking empty response.
     */
    private fun resolve(name: String): ExportedFunction = functions[name]
        ?: throw NoSuchElementException(
            "No exported function named '$name'. Known: ${names.sorted()}"
        )
}
