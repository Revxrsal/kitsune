package kitsune

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Runs the shadow jar through ProGuard, producing the jar the app actually
 * ships — renamed so the compiled Kotlin is harder to read, but with the names
 * the Rust host and the JVM launcher resolve *by string* left verbatim (see
 * `proguard-rules.pro` for that closed set).
 *
 * ## Why this owns `dist/lib/app.jar`
 *
 * The whole downstream pipeline — jdeps, the AOT cache, the host at runtime —
 * keys on one jar at one path. Rather than teach each of those to branch on
 * whether obfuscation ran, this task is the sole producer of that path in both
 * modes: with [enabled] off it copies the shadow jar through unchanged, with it
 * on it obfuscates. The graph downstream never sees the difference, so turning
 * the flag off cannot leave a half-wired build.
 *
 * ## Library jars
 *
 * ProGuard has to resolve every reference the jar makes into the JDK — for
 * renaming, so an override of a JDK method is not treated as a private name and
 * broken. Where the toolchain ships a `jmods/` directory (JDK 9-23), its whole
 * contents are handed over as `-libraryjars`: an over-approximation, but
 * resolution only needs the classes present.
 *
 * JDK 24+ can omit `jmods` entirely (JEP 493, linking run-time images without
 * JMODs), shipping only the packed `lib/modules` image — which proguard-core
 * cannot read as a library (it resolves nothing and every `java.lang` reference
 * dangles). So there, `bin/jimage extract` unpacks `lib/modules` into real
 * class files once, and each module directory is passed as a `-libraryjars`
 * root. That extraction is ~20s and JDK-wide, so it is cached under the Gradle
 * user home keyed by the exact JDK build ([jdkModulesCache]); a JDK with real
 * jmods never pays it.
 */
abstract class ProGuardTask : DefaultTask() {

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    /**
     * The ProGuard configuration (keep rules). Optional only so the task type
     * stays usable without one; in practice the pipeline always sets it, and a
     * run with no keep rules would rename the JNI surface and break the bridge.
     */
    @get:InputFile
    @get:Optional
    abstract val rulesFile: RegularFileProperty

    /** When false, the shadow jar is copied through untouched. See the class KDoc. */
    @get:Input
    abstract val obfuscating: Property<Boolean>

    /** ProGuard itself — `com.guardsquare:proguard-base` and its transitives. */
    @get:Classpath
    abstract val proguardClasspath: ConfigurableFileCollection

    /**
     * The toolchain supplying the `jmods` used as `-libraryjars` and the `java`
     * that runs ProGuard. `@Nested` so the JDK's vendor and version join the
     * up-to-date check — see [tool] — since a rename map is only reproducible
     * against the exact library classes it resolved against.
     */
    @get:Nested
    abstract val launcher: Property<JavaLauncher>

    /** The resolved JDK build, which [launcher] alone does not pin. See [buildId]. */
    @get:Input
    val javaBuild: Provider<String> get() = launcher.map { it.buildId }

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    /**
     * Where the JEP-493 fallback unpacks the JDK's `lib/modules`. A cache, not a
     * declared output: it lives outside the build tree (so `clean` does not force
     * a re-extract) and is keyed by JDK build, so it is `@Internal`.
     */
    @get:Internal
    abstract val jdkModulesCache: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun run() {
        val input = inputJar.get().asFile
        val output = outputJar.get().asFile
        output.parentFile.mkdirs()

        if (!obfuscating.get()) {
            input.copyTo(output, overwrite = true)
            logger.lifecycle("Obfuscation disabled; copied ${input.name} -> ${output.absolutePath}")
            return
        }

        // ProGuard appends nothing and will not overwrite in place cleanly; give
        // it a fresh target.
        output.delete()

        val libraryRoots = platformLibraryRoots()

        val args = buildList {
            add("-injars"); add(input.absolutePath)
            add("-outjars"); add(output.absolutePath)
            libraryRoots.forEach { add("-libraryjars"); add(it) }
            rulesFile.orNull?.asFile?.let { add("@${it.absolutePath}") }
        }

        execOps.javaexec {
            executable = launcher.get().tool("java")
            classpath = proguardClasspath
            mainClass.set("proguard.ProGuard")
            args(args)
        }

        require(output.isFile && output.length() > 0) { "ProGuard produced no jar at $output" }
        logger.lifecycle("Obfuscated jar: ${output.absolutePath} (${output.length() / (1L shl 20)} MB)")
    }

    /**
     * The `-libraryjars` roots ProGuard resolves the platform against. See the
     * class KDoc: jmods directly when present, otherwise a one-time extraction of
     * `lib/modules` cached per JDK build.
     */
    private fun platformLibraryRoots(): List<String> {
        val jmods = File(launcher.get().jmods)
        val jmodFiles = jmods.listFiles { f -> f.extension == "jmod" }?.sorted().orEmpty()
        if (jmodFiles.isNotEmpty()) {
            // The classes live under classes/ inside each jmod; the filter drops
            // the nested jars and the module descriptor, which are not library
            // classes and only produce noise.
            return jmodFiles.map { "${it.absolutePath}(!**.jar;!module-info.class)" }
        }

        val javaHome = jmods.parentFile
        val image = File(javaHome, "lib/modules")
        check(image.isFile) {
            "toolchain at $javaHome has neither jmods/ nor lib/modules; cannot resolve " +
                "platform classes for ProGuard"
        }

        // One directory per JDK build, reused across builds and projects. The
        // marker guards against a half-finished extraction being trusted.
        val key = launcher.get().buildId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = jdkModulesCache.get().asFile.resolve(key)
        val marker = dest.resolve(".extracted")
        if (!marker.exists()) {
            dest.deleteRecursively()
            dest.mkdirs()
            logger.lifecycle("Extracting JDK modules for ProGuard (one-time, ~20s): ${dest.absolutePath}")
            execOps.exec {
                executable = launcher.get().tool("jimage")
                args("extract", "--dir", dest.absolutePath, image.absolutePath)
            }
            marker.writeText(launcher.get().buildId)
        }

        // jimage extract lays each module out under its own subdirectory, so each
        // is its own classpath root — otherwise the module name would prefix every
        // package and nothing would resolve.
        return dest.listFiles { f -> f.isDirectory }?.sorted()?.map { it.absolutePath }.orEmpty()
    }
}
