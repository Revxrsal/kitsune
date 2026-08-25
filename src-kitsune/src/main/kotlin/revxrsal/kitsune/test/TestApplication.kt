package revxrsal.kitsune.test

import revxrsal.kitsune.annotation.KitsuneEntrypoint
import revxrsal.kitsune.app.KitsuneApplication

@KitsuneEntrypoint
object TestApplication : KitsuneApplication() {
    init {
        println("Application initialized from an object!")
    }
}
