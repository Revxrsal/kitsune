package revxrsal.kitsune.app

import kotlinx.coroutines.Job
import revxrsal.kitsune.ipc.FunctionHandler
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

class Bridge(private val functionHandler: FunctionHandler) {

    companion object {

        @JvmStatic
        external fun rustComplete(id: Long, data: ByteArray?, error: String?)
    }

    private val jobs = ConcurrentHashMap<Long, Job>()

    fun submit(id: Long, name: String, request: ByteArray) {
        val job = functionHandler.launchCall(name, request) { result ->
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

    fun cancel(id: Long) {
        jobs[id]?.cancel()
    }
}
