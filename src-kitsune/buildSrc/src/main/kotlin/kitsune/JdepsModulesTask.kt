package kitsune

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Runs `jdeps --print-module-deps` against a jar and writes the resulting
 * comma-separated module list to [modulesFile].
 */
abstract class JdepsModulesTask : DefaultTask() {

    @get:InputFile
    abstract val jar: RegularFileProperty

    @get:Input
    abstract val multiRelease: Property<String>

    @get:Input
    abstract val jdepsExecutable: Property<String>

    @get:OutputFile
    abstract val modulesFile: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun run() {
        val stdout = ByteArrayOutputStream()
        execOps.exec {
            executable = jdepsExecutable.get()
            args(
                "--print-module-deps",
                "--ignore-missing-deps",
                "--multi-release", multiRelease.get(),
                jar.get().asFile.absolutePath
            )
            standardOutput = stdout
        }
        val modules = stdout.toString(Charsets.UTF_8).trim()
        require(modules.isNotEmpty()) { "jdeps did not report any modules" }
        modulesFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(modules)
        }
        logger.lifecycle("Required modules: $modules")
    }
}
