package kitsune

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Runs the training entry point against the finished cache and fails the build
 * unless the cache actually engaged.
 *
 * A rejected AOT cache fails *open*: the JVM logs the reason and then starts
 * normally, so without this check a mismatch shows up as "no speedup" and
 * nothing else. `-Xlog:aot` carries the verdict; `-Xlog:class+load` proves the
 * application's own classes, not just the JDK's, came out of the cache.
 */
abstract class VerifyAotCacheTask : DefaultTask() {

    @get:InputDirectory
    abstract val runtimeDir: DirectoryProperty

    @get:InputFile
    abstract val jar: RegularFileProperty

    @get:InputFile
    abstract val cacheFile: RegularFileProperty

    @get:Input
    abstract val trainingMainClass: Property<String>

    @get:Input
    abstract val vmOptions: ListProperty<String>

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun run() {
        val output = ByteArrayOutputStream()
        execOps.exec {
            executable = runtimeDir.get().asFile.resolve("bin/java").absolutePath
            args(vmOptions.get())
            args("-XX:AOTCache=${cacheFile.get().asFile.absolutePath}")
            args("-Xlog:aot=info", "-Xlog:class+load=info")
            args("-cp", jar.get().asFile.absolutePath, trainingMainClass.get())
            standardOutput = output
            errorOutput = output
            // vmOptions carries -XX:AOTMode=on, so a rejected cache aborts VM
            // startup with a non-zero exit. Swallow it here and report below,
            // where the [aot] lines can say which flag actually disagreed.
            isIgnoreExitValue = true
        }
        val log = output.toString(Charsets.UTF_8)
        val lines = log.lineSequence()

        val problems = buildList {
            if (log.contains("Error occurred during initialization of VM")) {
                add("the VM refused to start under -XX:AOTMode=on")
            }
            if (!log.contains("Opened AOT cache")) {
                add("the JVM never opened the cache")
            }
            if (log.contains("Unable to use AOT cache")) {
                add("the JVM rejected the cache")
            }
            if (log.contains("full module graph: disabled")) {
                add("the archived module graph was disabled, which invalidates aot-linked classes")
            }
            val mainClass = trainingMainClass.get()
            val loadedFromCache = lines.any {
                it.contains(mainClass) && it.contains("source: shared objects file")
            }
            if (!loadedFromCache) {
                add("$mainClass was not loaded from the cache")
            }
        }

        if (problems.isNotEmpty()) {
            // The JVM always explains itself on the [aot] lines; surface them rather
            // than making the reader re-run the command by hand.
            val diagnostics = lines
                .filter { it.contains("[aot]") && (it.contains("error") || it.contains("warning")) }
                .joinToString("\n") { "    $it" }
            throw GradleException(
                buildString {
                    appendLine("AOT cache verification failed: ${problems.joinToString("; ")}.")
                    appendLine("The vmOptions used to build the cache must match the ones the host process passes.")
                    if (diagnostics.isNotBlank()) {
                        appendLine("JVM said:")
                        append(diagnostics)
                    }
                }
            )
        }
        logger.lifecycle("AOT cache verified: full module graph enabled, ${trainingMainClass.get()} loaded from cache")
    }
}
