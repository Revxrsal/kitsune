package revxrsal.kitsune.app

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import revxrsal.kitsune.annotation.ExportEvent
import revxrsal.kitsune.annotation.ExportFunction
import revxrsal.kitsune.annotation.Listener
import revxrsal.kitsune.generated.GeneratedEvents
import revxrsal.kitsune.generated.GeneratedFunctions
import kotlin.time.Duration.Companion.milliseconds

lateinit var application: KitsuneApplication

abstract class KitsuneApplication {

    internal val functionsHandler = GeneratedFunctions.handler()
    internal val eventsHandler = GeneratedEvents.handler()

    init {
        require(!::application.isInitialized) {
            "Application already initialized"
        }
        application = this
    }
}

/** No arguments: the generated wrapper decodes nothing. */
@ExportFunction
fun version(): String {
    return "1.0"
}

/** All arguments mandatory: decoded and called directly. */
@ExportFunction(name = "add")
fun add(a: Int, b: Int): Int {
    return a + b
}

/** Defaulted arguments: the generated wrapper masks and may go through `reverse$default`. */
@ExportFunction
fun reverse(input: String = "", times: Int = 1): String {
    return input.reversed().repeat(times)
}

/**
 * Nullable *and* defaulted, the shape that needs the decoder's mask.
 *
 * `{}` and `{"text": null}` are different calls — the first takes the default,
 * the second really means null — and nothing in the decoded value distinguishes
 * them. Only the mask does, because it records which keys the payload carried.
 */
@ExportFunction
fun label(text: String? = "untitled"): String = text ?: "<null>"

/** A member of an object, to cover the non-top-level call site. */
object Greeter {

    @ExportFunction(name = "greet")
    fun greet(name: String = "world"): String = "hello, $name"
}

/** Returns Unit: the wrapper replies with zero bytes. */
@ExportFunction
fun log(message: String = "ping", level: Int = 1) {
    println("[$level] $message")
}

// --- suspending exports ----------------------------------------------------

/** Suspending, no defaults: called directly from the suspending wrapper. */
@ExportFunction
suspend fun fetch(url: String): String {
    delay(10.milliseconds)
    return "fetched $url"
}

/** Suspending with defaults: goes through `poll$default` with a continuation. */
@ExportFunction
suspend fun poll(source: String = "default", attempts: Int = 3): String {
    delay(10.milliseconds)
    return "polled $source x$attempts"
}

/** Suspending and Unit-returning: still returns `Object` at the JVM level. */
@ExportFunction
suspend fun warm(target: String = "cache") {
    delay(10.milliseconds)
    println("warmed $target")
}

/** Suspending member of an object. */
object Store {

    @ExportFunction(name = "load")
    suspend fun load(key: String = "k", limit: Int = 10): String {
        delay(10.milliseconds)
        return "loaded $key/$limit"
    }
}

// --- events ----------------------------------------------------------------

@ExportEvent(id = "clicked")
@Serializable
class ButtonClicked(val x: Int, val y: Int)

@Listener
fun onButtonClicked(event: ButtonClicked) {
    println("clicked at ${event.x}, ${event.y}")
}

@Listener
suspend fun onButtonClickedAsync(event: ButtonClicked) {
    delay(10.milliseconds)
    println("async click handled at ${event.x}, ${event.y}")
}
