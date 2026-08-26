package revxrsal.kitsune

import revxrsal.kitsune.app.KitsuneApplication
import revxrsal.kitsune.app.KitsuneEntrypoint

@KitsuneEntrypoint
object TestApplication : KitsuneApplication() {
    init {
        println("Application initialized!")
    }
}
