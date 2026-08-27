package kitsune

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Runs `jdeps --print-module-deps` against a jar and writes the resulting
 * comma-separated module list to [modulesFile].
 *
 * This runs on every jar change, which is the point: it is how the build learns
 * whether the module set moved. [JlinkTask] consumes only [modulesFile], and
 * Gradle fingerprints that by content, so an unchanged module set leaves the
 * expensive link up-to-date even though this task re-ran.
 */
abstract class JdepsModulesTask : DefaultTask() {

    @get:InputFile
    abstract val jar: RegularFileProperty

    @get:Input
    abstract val multiRelease: Property<String>

    /** The toolchain whose `bin/jdeps` runs. See [tool] for why this is `@Nested`. */
    @get:Nested
    abstract val launcher: Property<JavaLauncher>

    /** The resolved JDK build, which [launcher] alone does not pin. See [buildId]. */
    @get:Input
    val javaBuild: Provider<String> get() = launcher.map { it.buildId }

    @get:OutputFile
    abstract val modulesFile: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun run() {
        val stdout = ByteArrayOutputStream()
        execOps.exec {
            executable = launcher.get().tool("jdeps")
            args(
                "--print-module-deps",
                "--ignore-missing-deps",
                "--multi-release", multiRelease.get(),
                jar.get().asFile.absolutePath
            )
            standardOutput = stdout
        }
        val raw = stdout.toString(Charsets.UTF_8).trim()
        require(raw.isNotEmpty()) { "jdeps did not report any modules" }

        // jdeps prints these sorted today, but that is incidental to its
        // implementation and nothing in the tool's contract promises it. Since
        // the file's *bytes* are what decide whether the runtime is relinked,
        // normalizing here means a reordering could never cost a rebuild.
        val modules = raw.splitToSequence(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .joinToString(",")

        modulesFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(modules)
        }
        logger.lifecycle("Required modules: $modules")
    }
}
