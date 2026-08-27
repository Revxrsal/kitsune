package kitsune

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
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
 * ProGuard has to resolve every reference the jar makes into the JDK, so the
 * toolchain's whole `jmods/` directory is passed as `-libraryjars`. That is an
 * over-approximation — jdeps computes the *minimal* module set elsewhere — but
 * resolution only needs the classes to be *present*, and handing ProGuard every
 * module means a new dependency on some corner of the JDK never turns into a
 * "can't find referenced class" the day it is added.
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

        val jmods = File(launcher.get().jmods)
        val libraryJars = jmods.listFiles { f -> f.extension == "jmod" }?.sorted().orEmpty()
        require(libraryJars.isNotEmpty()) {
            "no .jmod files under $jmods to hand ProGuard as -libraryjars"
        }

        val args = buildList {
            add("-injars"); add(input.absolutePath)
            add("-outjars"); add(output.absolutePath)
            libraryJars.forEach {
                add("-libraryjars")
                // The classes live under classes/ inside the jmod; the filter drops
                // the nested jars and the module descriptor, which are not library
                // classes and only produce noise.
                add("${it.absolutePath}(!**.jar;!module-info.class)")
            }
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
}
