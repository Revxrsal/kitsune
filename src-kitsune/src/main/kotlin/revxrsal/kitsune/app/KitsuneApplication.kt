package revxrsal.kitsune.app

import revxrsal.kitsune.generated.GeneratedEvents
import revxrsal.kitsune.generated.GeneratedFunctions

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
