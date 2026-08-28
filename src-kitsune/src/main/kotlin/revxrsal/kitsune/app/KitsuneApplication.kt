package revxrsal.kitsune.app

import dev.dirs.BaseDirectories
import revxrsal.kitsune.generated.GeneratedEvents
import revxrsal.kitsune.generated.GeneratedFunctions
import revxrsal.kitsune.generated.GeneratedTauriConfig
import kotlin.io.path.Path

private val baseDirs = BaseDirectories.get()
lateinit var application: KitsuneApplication

abstract class KitsuneApplication {

    val functionsHandler = GeneratedFunctions.handler()
    val eventsHandler = GeneratedEvents.handler()
    val config = GeneratedTauriConfig.config
    val configDir = Path(baseDirs.configDir, config.identifier)
    val appDataDir = Path(baseDirs.dataDir, config.identifier)
    val appLocalDataDir = Path(baseDirs.dataLocalDir, config.identifier)
    val cacheDir = Path(baseDirs.cacheDir, config.identifier)
    val logDir = Path(baseDirs.dataLocalDir, config.identifier, "logs")

    init {
        require(!::application.isInitialized) {
            "Application already initialized"
        }
        application = this
    }
}
