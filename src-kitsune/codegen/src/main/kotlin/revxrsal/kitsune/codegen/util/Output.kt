package revxrsal.kitsune.codegen.util

import com.google.devtools.ksp.processing.KSPLogger
import java.io.File

/**
 * Writes [contents] to [file], and only when they differ from what is there.
 *
 * Used by the processors that generate for the other two languages, which write
 * straight into source trees outside this Gradle project rather than through
 * KSP's `CodeGenerator`; see `TypeScriptProcessor` for why that channel is the
 * wrong one for them.
 *
 * The comparison is not a micro-optimisation. Both files sit under a watcher
 * (Vite's dev server on one, `cargo`'s mtime check on the other), and rewriting
 * identical bytes on every Gradle build is what makes a Kotlin-only change
 * reload the browser or relink the Rust host for nothing.
 */
fun writeIfChanged(file: File, contents: String, logger: KSPLogger, what: String) {
    file.parentFile?.mkdirs()
    if (file.isFile && file.readText() == contents) return
    file.writeText(contents)
    logger.info("Kitsune: wrote $what to $file")
}
