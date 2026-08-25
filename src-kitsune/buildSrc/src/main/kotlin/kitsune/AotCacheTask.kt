package kitsune

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Builds a JDK 25 AOT cache (JEP 483 + JEP 515) for the application jar.
 *
 * The cache records which classes were loaded and linked during a training run,
 * plus method profiling data, so subsequent starts skip that work. It is built
 * with the *bundled* runtime's own launcher, not the build JDK, because a cache
 * is only valid for the exact JVM build that produced it.
 *
 * The two-step `record` -> `create` form is used rather than the one-step
 * `-XX:AOTCacheOutput`, for two reasons: the one-step form is a feature of the
 * `java` launcher that internally forks two JVMs (double the heap, awkward on a
 * constrained CI runner), and splitting the steps lets Gradle keep the
 * intermediate configuration out of the shipped image.
 *
 * ## Flag matching is not optional
 *
 * [vmOptions] is applied to *both* training runs and must be byte-identical to
 * what the host process passes to `JNI_CreateJavaVM`. The JVM validates these
 * at load time and a mismatch is fatal to the cache, not a soft downgrade:
 *
 *  - `-XX:+UseCompactObjectHeaders` differing either way => "Unable to use AOT
 *    cache. The AOT cache's UseCompactObjectHeaders setting ... does not equal
 *    the current setting."
 *  - `--enable-native-access=ALL-UNNAMED` present at runtime but not at dump
 *    time => "Disabling optimized module handling" and then "AOT cache has
 *    aot-linked classes. It cannot be used when archived full module graph is
 *    not used." The cache is rejected wholesale.
 *
 * That is why the same list is also written to `vmoptions.txt` and read back by
 * the host process, instead of being spelled out in two places.
 */
abstract class AotCacheTask : DefaultTask() {

    /** The jlink image whose `bin/java` performs the training runs. */
    @get:InputDirectory
    abstract val runtimeDir: DirectoryProperty

    @get:InputFile
    abstract val jar: RegularFileProperty

    /** Fully-qualified class holding the training entry point. */
    @get:Input
    abstract val trainingMainClass: Property<String>

    /** VM flags shared by the training runs and the production process. */
    @get:Input
    abstract val vmOptions: ListProperty<String>

    /** Intermediate profile; deliberately kept in build/, not in the shipped image. */
    @get:Internal
    abstract val configurationFile: RegularFileProperty

    @get:OutputFile
    abstract val cacheFile: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun run() {
        val java = runtimeDir.get().asFile.resolve("bin/java").absolutePath
        // Absolute, so the recorded classpath matches what the host passes.
        val classpath = jar.get().asFile.absolutePath
        val config = configurationFile.get().asFile.also { it.parentFile.mkdirs() }
        val cache = cacheFile.get().asFile.also { it.parentFile.mkdirs() }
        val shared = vmOptions.get()

        logger.lifecycle("AOT training run (${trainingMainClass.get()})")
        execOps.exec {
            executable = java
            args(shared)
            args("-XX:AOTMode=record", "-XX:AOTConfiguration=${config.absolutePath}")
            args("-cp", classpath, trainingMainClass.get())
        }

        // The create step replays the recorded profile; it takes no main class.
        execOps.exec {
            executable = java
            args(shared)
            args("-XX:AOTMode=create", "-XX:AOTConfiguration=${config.absolutePath}")
            args("-XX:AOTCache=${cache.absolutePath}")
            args("-cp", classpath)
        }

        require(cache.isFile && cache.length() > 0) { "jlink runtime produced no AOT cache at $cache" }
        logger.lifecycle("AOT cache: ${cache.absolutePath} (${cache.length() / (1L shl 20)} MB)")
    }
}
