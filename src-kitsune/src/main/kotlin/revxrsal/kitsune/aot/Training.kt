package revxrsal.kitsune.aot

import java.io.File
import java.util.jar.JarFile
import kotlin.collections.iterator

/**
 * Entry point for the AOT training run (`:aotCache`).
 *
 * The app's real `main` deliberately isn't used. Training runs under the bundled
 * `runtime/bin/java` with no Rust host in the process, so everything declared
 * `external` is unbound. That is not a problem in itself: a class holding any
 * number of native declarations loads and links fine, because the JVM binds a
 * native only at its first *invocation*. Calling one is what throws
 * `UnsatisfiedLinkError`, and since the AOT cache is mandatory that fails the
 * build.
 *
 * So the rule for anything added here is narrow: never *call* a native. Adding
 * native declarations, however many, needs no change to this file.
 *
 * ## Scope
 *
 * The classes to warm are discovered from the jar at runtime, not listed by
 * hand, so new code is covered the moment it compiles. The scope is this class's
 * own package tree, `revxrsal.kitsune.**`. That is deliberate: the shadow jar
 * also carries ~1000 kotlin-stdlib classes, and archiving all of them would
 * inflate the cache with code the app never touches. Stdlib classes that *are*
 * used get archived anyway, by being used.
 *
 * The one thing to know: code outside `revxrsal.kitsune` is not discovered. Move
 * this class if the root package ever changes.
 *
 * ## Where real warm-up goes
 *
 * Loading and linking is what this pass records. Once the Kotlin side grows a
 * JDBC pool, an HTTP client or serialization, constructing those here (pure JVM
 * work, no native calls) is what the cache actually pays for, and is worth more
 * than the class scan below.
 */
object Training {

    @JvmStatic
    fun main(args: Array<String>) {
        val (linked, skipped) = linkOwnClasses()
        // An empty scan means the package prefix stopped matching, and a cache
        // with none of the app's classes in it is worse than useless, since it
        // still loads and still looks like it worked.
        check(linked > 0) { "AOT training linked no application classes" }
        println("AOT training: linked $linked classes" + if (skipped > 0) " ($skipped skipped)" else "")
    }

    private fun linkOwnClasses(): Pair<Int, Int> {
        val loader = Training::class.java.classLoader
        val prefix = Training::class.java.packageName.replace('.', '/') + "/"
        var linked = 0
        var skipped = 0

        JarFile(ownJar()).use { jar ->
            for (entry in jar.entries()) {
                val path = entry.name
                if (!path.startsWith(prefix) || !path.endsWith(".class")) continue
                if (path.endsWith("module-info.class")) continue

                val name = path.removeSuffix(".class").replace('/', '.')
                // initialize = false. Loading and linking is what gets archived;
                // running arbitrary static initialisers here is the one way to
                // reintroduce the native-call hazard this class exists to avoid.
                runCatching { Class.forName(name, false, loader) }
                    .onSuccess { linked++ }
                    .onFailure { skipped++; System.err.println("AOT training: skipped $name ($it)") }
            }
        }
        return linked to skipped
    }

    private fun ownJar(): File {
        val source = checkNotNull(Training::class.java.protectionDomain?.codeSource?.location) {
            "no code source; training must run from the shadow jar"
        }
        val file = File(source.toURI())
        check(file.isFile) { "training must run from the shadow jar, got $file" }
        return file
    }
}
