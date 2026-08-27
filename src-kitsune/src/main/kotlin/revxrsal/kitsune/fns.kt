package revxrsal.kitsune

import kotlinx.serialization.Serializable
import revxrsal.kitsune.app.application
import revxrsal.kitsune.event.ExportEvent
import revxrsal.kitsune.event.Listener
import revxrsal.kitsune.functions.ExportFunction

@ExportEvent
@Serializable
data class SomeEvent(val id: Int, val name: String = "hi")

@ExportFunction
fun emitEvent() {
    application.eventsHandler.dispatch(SomeEvent(1))
}

@Listener
fun onSomeEvent(event: SomeEvent) {
    println("Received $event")
}

@ExportFunction
fun warmUp() {
    println("JNI warmed up")
}
