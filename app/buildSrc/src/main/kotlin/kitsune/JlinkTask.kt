package kitsune

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Runs `jlink` to build a trimmed runtime image containing only the modules
 * listed in [modulesFile].
 *
 * Everything here beyond `--add-modules` exists to make the image smaller. The
 * image is dominated by `lib/server/libjvm.so` (~29 MB) and `lib/modules`
 * (~12 MB), neither of which jlink can shrink much, so the real lever is
 * keeping the module list minimal — which [JdepsModulesTask] already does by
 * computing it instead of guessing.
 */
abstract class JlinkTask : DefaultTask() {

    @get:InputFile
    abstract val modulesFile: RegularFileProperty

    @get:Input
    abstract val jlinkExecutable: Property<String>

    @get:Input
    abstract val jmodsPath: Property<String>

    /** `zip-0`..`zip-9`. zip-9 buys very little over zip-6 but costs nothing at runtime. */
    @get:Input
    abstract val compression: Property<String>

    /** Which HotSpot variants to keep. `server` drops `client`/`minimal`. */
    @get:Input
    abstract val vmVariant: Property<String>

    /**
     * Path to `objcopy`, used to strip native debug symbols from the shipped
     * `.so` files. Most vendor JDKs (Temurin included) already ship stripped
     * binaries, so this is usually a no-op — it only pays off on distro builds
     * that leave symbols in. Left unset, the plugin is skipped.
     */
    @get:Input
    @get:Optional
    abstract val objcopyExecutable: Property<String>

    /** jlink `--exclude-files` patterns, e.g. `/java.base/bin/keytool`. */
    @get:Input
    abstract val excludeFiles: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @get:Inject
    abstract val fsOps: FileSystemOperations

    @TaskAction
    fun run() {
        val target = outputDir.get().asFile
        // jlink refuses to write into an existing directory
        fsOps.delete { delete(target) }

        val options = buildList {
            add("--module-path"); add(jmodsPath.get())
            add("--add-modules"); add(modulesFile.get().asFile.readText().trim())
            add("--strip-debug")
            add("--no-man-pages")
            add("--no-header-files")
            add("--compress=${compression.get()}")
            add("--vm=${vmVariant.get()}")
            // Collapses the per-module legal/ trees into one copy, failing loudly
            // if two modules ship differing files under the same name.
            add("--dedup-legal-notices=error-if-not-same-content")
            objcopyExecutable.orNull?.let { add("--strip-native-debug-symbols=objcopy=$it") }
            excludeFiles.get().forEach { add("--exclude-files=$it") }
            add("--output"); add(target.absolutePath)
        }

        execOps.exec {
            executable = jlinkExecutable.get()
            args(options)
        }
        logger.lifecycle("Runtime image: ${target.absolutePath} (${humanSize(target.diskUsage())})")
    }

    private fun File.diskUsage(): Long =
        walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        else -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    }
}
