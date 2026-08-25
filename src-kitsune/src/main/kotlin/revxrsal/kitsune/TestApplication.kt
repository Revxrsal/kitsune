package revxrsal.kitsune

import revxrsal.kitsune.app.KitsuneEntrypoint
import revxrsal.kitsune.app.KitsuneApplication

@KitsuneEntrypoint
object TestApplication : KitsuneApplication() {
    init {
        println("Application initialized!")
    }
}
