package revxrsal.kitsune.ipc

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/**
 * The scope every suspending export and listener runs in.
 *
 * Deliberately **not** `GlobalScope`, for four reasons that all bite in a JVM
 * embedded in a host process:
 *
 * 1. `GlobalScope` has no lifecycle. Nothing can cancel work started in it, so
 *    the host has no way to tell the Kotlin side to stop — see [shutdown].
 * 2. Its children are not supervised. Two exports launched from unrelated host
 *    calls would be siblings under one job, and a failure in either cancels the
 *    other. [SupervisorJob] makes each direct child fail alone.
 * 3. An exception escaping a `launch` has nowhere to go but the thread's default
 *    handler. Across a JNI boundary that is a silent loss, hence the explicit
 *    [CoroutineExceptionHandler].
 * 4. It is `@DelicateCoroutinesApi` precisely because of the above.
 *
 * [Dispatchers.Default] is the base because it is sized for CPU work and is the
 * neutral choice for code we do not control. An export that blocks — JDBC, a
 * synchronous HTTP client — is responsible for its own `withContext(Dispatchers.IO)`,
 * exactly as it would be anywhere else.
 */
object KitsuneScope : CoroutineScope {

    private val exceptionHandler = CoroutineExceptionHandler { context, throwable ->
        // Not rethrown: this runs on a coroutine's thread, and throwing here
        // would take down a dispatcher thread rather than reach the host.
        System.err.println("Uncaught exception in ${context[CoroutineName]?.name ?: "kitsune"}:")
        throwable.printStackTrace()
    }

    override val coroutineContext: CoroutineContext =
        SupervisorJob() + Dispatchers.Default + CoroutineName("kitsune") + exceptionHandler

    /**
     * Cancels every in-flight export and listener.
     *
     * The scope is unusable afterwards — a cancelled [SupervisorJob] rejects new
     * children — so this belongs at host teardown and nowhere else.
     */
    fun shutdown() {
        cancel()
    }
}
