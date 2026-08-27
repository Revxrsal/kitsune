package kitsune

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
 import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
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
 *
 * Linking is expensive and the module set rarely moves, so [modulesFile] is
 * deliberately the only content-bearing input: Gradle fingerprints it by
 * content, which leaves this task up-to-date across the ordinary code change
 * that rebuilds the jar and re-runs jdeps to the same answer.
 */
abstract class JlinkTask : DefaultTask() {

    @get:InputFile
    abstract val modulesFile: RegularFileProperty

    /**
     * The toolchain supplying both `bin/jlink` and the `jmods` it links from.
     *
     * `@Nested` rather than a pair of path strings, so the JDK's vendor and
     * version join the up-to-date check — see [tool]. A JDK upgraded in place
     * would otherwise leave this task reporting up-to-date while the shipped
     * image, and so the AOT cache trained against it, came from the old one.
     */
    @get:Nested
    abstract val launcher: Property<JavaLauncher>

    /** The resolved JDK build, which [launcher] alone does not pin. See [buildId]. */
    @get:Input
    val javaBuild: Provider<String> get() = launcher.map { it.buildId }

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
        val jdk = launcher.get()
        val target = outputDir.get().asFile
        // jlink refuses to write into an existing directory
        fsOps.delete { delete(target) }

        val options = buildList {
            add("--module-path"); add(jdk.jmods)
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
            executable = jdk.tool("jlink")
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
