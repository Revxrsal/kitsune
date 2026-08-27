package revxrsal.kitsune.codegen.rust

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import revxrsal.kitsune.codegen.util.Annotations
import revxrsal.kitsune.codegen.util.RESERVED_PACKAGE
import revxrsal.kitsune.codegen.util.Runtime
import revxrsal.kitsune.codegen.util.isReservedPackageName
import revxrsal.kitsune.codegen.util.isSubclassOf
import revxrsal.kitsune.codegen.util.jniBinaryName
import revxrsal.kitsune.codegen.util.symbolsAnnotatedWith
import revxrsal.kitsune.codegen.util.writeIfChanged
import java.io.File

/** Discovered by KSP through `META-INF/services`; must be public with a no-arg constructor. */
class EntrypointProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        EntrypointProcessor(environment.options, environment.logger)
}

/** Absolute path of the file to write. Set from `kitsune.entrypoint` in the build. */
const val ENTRYPOINT_OPTION = "kitsune.entrypoint"

/**
 * Finds the `@KitsuneEntrypoint` declaration and writes the Rust host's
 * `entrypoint.rs`.
 *
 * The host has to name that class as a string (`FindClass` takes a binary name
 * and there is no Rust type to hang it off), so before this processor existed
 * the name was hand-written on the Rust side and nothing checked it against the
 * Kotlin source. A moved or renamed class compiled cleanly on both sides and
 * failed at startup with `NoClassDefFoundError`. Generating the string from the
 * annotation is what removes that class of failure: the two cannot disagree,
 * because only one of them is written by a person.
 *
 * ## Where the file goes
 *
 * Straight to the configured path with ordinary file I/O, for the same reason
 * `TypeScriptProcessor` does it: the output is not an input to any Kotlin
 * compilation, and KSP's `CodeGenerator` can only write inside
 * `build/generated/ksp`, which is not a place `cargo` will look.
 *
 * The cost is the same too: KSP does not track the file, so deleting it by hand
 * does not make `kspKotlin` out of date and `./gradlew clean` is what brings it
 * back. What keeps its *contents* honest is that the two aggregating processors
 * force every source file to be reprocessed whenever any of them changes, so a
 * round never sees a source tree with the entrypoint edited out of it.
 *
 * ## Diagnostics
 *
 * Unlike the TypeScript pass, this one reports its own errors rather than
 * staying quiet: it is the only processor that reads `@KitsuneEntrypoint`, so
 * nothing else has already said the annotation is on something the host cannot
 * hand control to. A rejected entrypoint fails the build and leaves the existing file
 * alone; overwriting it with a stub would turn one clear error into a second,
 * unrelated Rust compile error on the next `cargo build`.
 */
class EntrypointProcessor(
    options: Map<String, String>,
    private val logger: KSPLogger,
) : SymbolProcessor {

    /** Blank means the build did not configure `kitsune.entrypoint`; nothing is generated. */
    private val output = options[ENTRYPOINT_OPTION]?.takeIf { it.isNotBlank() }

    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList()
        invoked = true

        val path = output ?: run {
            logger.info("Kitsune: no '$ENTRYPOINT_OPTION' option; skipping the Rust entrypoint.")
            return emptyList()
        }

        val declaration = resolveEntrypoint(resolver) ?: return emptyList()
        val internalName = declaration.jniBinaryName() ?: run {
            // Only a declaration nested inside a function gets here; its binary
            // name is compiler-assigned and not a thing to generate against.
            logger.error("@KitsuneEntrypoint cannot be applied to a local class.", declaration)
            return emptyList()
        }

        writeIfChanged(
            file = File(path),
            contents = renderEntrypoint(
                Entrypoint(
                    internalName = internalName,
                    // The two forms differ only in the separator; `$` already
                    // separates nested names in both.
                    binaryName = internalName.replace('/', '.'),
                    isObject = declaration.classKind == ClassKind.OBJECT,
                )
            ),
            logger = logger,
            what = "the Rust entrypoint",
        )
        return emptyList()
    }

    /**
     * The single annotated declaration, or `null` when there is not exactly one
     * usable candidate.
     *
     * "Not exactly one" is an error in both directions. Zero means the host has
     * nothing to hand control to and the app cannot start; more than one means the
     * generated file would have to pick, and picking silently, by a source
     * order that is not even stable, is how you ship a build that starts the
     * wrong application.
     */
    private fun resolveEntrypoint(resolver: Resolver): KSClassDeclaration? {
        var annotated = 0
        val candidates = mutableListOf<KSClassDeclaration>()

        for (symbol in resolver.symbolsAnnotatedWith(Annotations.ENTRYPOINT)) {
            annotated++
            // @KitsuneEntrypoint is @Target(CLASS), so this is defensive, but a
            // malformed symbol arrives as a plain KSAnnotated, and a cast would
            // fail the build with a ClassCastException and no source location.
            if (symbol !is KSClassDeclaration) {
                logger.error("@KitsuneEntrypoint is only applicable to classes and objects.", symbol)
                continue
            }
            if (symbol.isUsableEntrypoint()) candidates += symbol
        }

        if (candidates.size == 1) return candidates.single()

        if (candidates.isEmpty()) {
            // Only when nothing was annotated at all. A rejected candidate has
            // already been reported against its own declaration, and "found no
            // @KitsuneEntrypoint declaration" on top of that would contradict
            // the source the reader is looking at.
            if (annotated == 0) {
                // Also the shape a moved annotation package takes (see
                // Annotations, which matches on names the compiler never
                // checks), so the message names both possibilities.
                logger.error(
                    "Kitsune codegen found no @KitsuneEntrypoint declaration. Annotate the " +
                        "class or object that extends ${Runtime.APPLICATION}; if one is " +
                        "annotated, check that the annotations still live in " +
                        "${Annotations.PACKAGE}."
                )
            }
            return null
        }

        val names = candidates.mapNotNull { it.qualifiedName?.asString() }.sorted()
        for (candidate in candidates) {
            logger.error(
                "Multiple @KitsuneEntrypoint declarations: ${names.joinToString(", ")}. " +
                    "Exactly one may be the entry point.",
                candidate,
            )
        }
        return null
    }

    /**
     * Whether the host can actually hand control to this declaration through JNI.
     *
     * Every rejection here is something that compiles on the Kotlin side and
     * fails on the Rust side at startup, with a message about a class
     * descriptor rather than about the annotation, or worse, without failing at
     * all. Checking at codegen time is what turns each into an error against the
     * declaration.
     */
    private fun KSClassDeclaration.isUsableEntrypoint(): Boolean {
        if (isReservedPackageName()) {
            logger.error("Package '$RESERVED_PACKAGE' is reserved for generated code.", this)
            return false
        }
        if (classKind != ClassKind.CLASS && classKind != ClassKind.OBJECT) {
            logger.error(
                "@KitsuneEntrypoint must be on a class or an object, not " +
                    "${classKind.type.lowercase()}.",
                this,
            )
            return false
        }
        if (isCompanionObject) {
            // A companion looks like an ordinary object and is not one here. Its
            // `init` block runs from the *enclosing* class's initializer, not
            // from `Outer$Companion`'s, so the load the generated code would
            // emit initializes the wrong class and silently does nothing.
            logger.error("An entry point cannot be a companion object.", this)
            return false
        }
        if (Modifier.ABSTRACT in modifiers) {
            logger.error("An entry point cannot be abstract.", this)
            return false
        }
        if (Modifier.INNER in modifiers) {
            // A JNI constructor call passes no outer instance, and there is no
            // sensible one for the host to invent.
            logger.error("An entry point cannot be an inner class.", this)
            return false
        }
        if (!isPublic()) {
            logger.error("An entry point must be public.", this)
            return false
        }
        // An object has no constructor to call (the host loads its class and
        // lets the class initializer build it), so this only asks of a class.
        if (classKind == ClassKind.CLASS && !hasNoArgConstructor()) {
            logger.error(
                "An entry point needs a public no-argument constructor; the host calls it with " +
                    "no arguments.",
                this,
            )
            return false
        }
        if (!isSubclassOf(Runtime.APPLICATION)) {
            // Not tidiness: the base class's initializer is what publishes the
            // `application` singleton, and without it every bridge call fails on
            // an uninitialized `lateinit` rather than on anything that points
            // back here.
            logger.error("An entry point must extend ${Runtime.APPLICATION}.", this)
            return false
        }
        return true
    }
}

/**
 * Whether the class exposes a public `()V` constructor to the JVM.
 *
 * A constructor whose parameters all have defaults counts: Kotlin emits the
 * extra parameterless constructor for that case, which is the one `new_object`
 * will find.
 */
private fun KSClassDeclaration.hasNoArgConstructor(): Boolean =
    getConstructors().any { constructor ->
        constructor.isPublic() && constructor.parameters.all { it.hasDefault }
    }
