package revxrsal.kitsune.app

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

object Bridge {

    private val jobs = ConcurrentHashMap<Long, Job>()

    @JvmStatic
    fun submit(id: Long, name: String, request: ByteArray) {
        val job = application.functionsHandler.launchCall(name, request) { result ->
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
