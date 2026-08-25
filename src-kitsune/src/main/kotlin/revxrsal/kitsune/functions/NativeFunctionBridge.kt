package revxrsal.kitsune.functions

import kotlinx.coroutines.Job
import revxrsal.kitsune.app.application
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

object NativeFunctionBridge {

    private val jobs = ConcurrentHashMap<Long, Job>()

    /**
     * Runs the export at [ordinal] and completes the host's call [id] with the
     * result.
     *
     * [ordinal] rather than a name: the host reads it straight out of the head of
     * the frame, so no `String` is built on either side of the JNI boundary — a
     * `NewStringUTF` per call, plus the copy behind it, for a value that is only
     * ever used as a map key.
     *
     * [request] is the payload with the ordinal already stripped, so the decoder
     * can read it from index zero and no slice has to be copied out of it.
     */
    @JvmStatic
    fun submit(id: Long, ordinal: Int, request: ByteArray) {
        val job = application.functionsHandler.launchCall(ordinal, request) { result ->
            result.fold(
                onSuccess = { rustComplete(id, it, null) },
                onFailure = { rustComplete(id, null, "${it.javaClass.simpleName}: ${it.message}") },
            )
        }
        jobs[id] = job
        // Fires even if the coroutine was cancelled before onComplete could run.
        job.invokeOnCompletion { cause ->
            if (jobs.remove(id) != null && cause is CancellationException) {
                rustComplete(id, null, "cancelled")
            }
        }
    }

    @JvmStatic
    fun cancel(id: Long) {
        jobs[id]?.cancel()
    }

    @JvmStatic
    external fun rustComplete(id: Long, data: ByteArray?, error: String?)

}
