package kitsune

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * Configuration for the `kitsune.jlink` convention plugin, and for the KSP
 * processors that write the frontend's bindings and the Rust host's entrypoint.
 *
 * Those settings are declared here rather than in a `ksp { }` block so
 * that everything about "how this module becomes an app" is configured in one
 * place. The convention plugin does not read them — the root build passes them
 * on to KSP as processor options, which is the only channel a processor has.
 */
abstract class KitsuneExtension {

    /**
     * The TypeScript file the codegen writes bindings for every
     * `@ExportFunction` and `@ExportEvent` into.
     *
     * It lives outside this Gradle project, in the frontend's source tree, and
     * is overwritten in place on every build that changes the exported surface —
     * so point it at a generated file and check it in only as one.
     *
     * Left unset, no bindings are generated at all.
     */
    abstract val bindings: RegularFileProperty

    /**
     * Module specifier the generated file imports `Bridge` from, resolved
     * relative to [bindings] itself.
     *
     * Defaults to `./Bridge`, which is right whenever the two files are
     * siblings.
     */
    abstract val bridgeImport: Property<String>

    /**
     * The Rust file the codegen writes the `@KitsuneEntrypoint` handover into.
     *
     * Like [bindings], it lives outside this Gradle project — in the Rust host's
     * source tree — and is overwritten in place whenever the annotated class
     * moves or is renamed. The host names that class as a JNI string, so this is
     * what keeps the string and the Kotlin declaration from drifting apart.
     *
     * Left unset, no entrypoint is generated, and a module with no
     * `@KitsuneEntrypoint` class is not an error.
     */
    abstract val entrypoint: RegularFileProperty

    /**
     * VM flags shared by the AOT training runs and the host process that calls
     * `JNI_CreateJavaVM`. These are written to `dist/lib/vmoptions.txt`, which
     * the host reads at startup — a single source of truth, because several of
     * these flags invalidate the AOT cache if the two sides disagree.
     *
     * Do not put paths in here. The host appends `-Djava.class.path` and
     * `-XX:AOTCache` itself, resolved relative to wherever it was installed.
     */
    abstract val vmOptions: ListProperty<String>

    /**
     * [vmOptions] plus the flags that only make sense when *consuming* a cache.
     *
     * `-XX:AOTMode=on` is the one that matters: the default `auto` mode treats a
     * rejected cache as a soft downgrade — it logs the reason and starts anyway,
     * so a mismatch reaches production looking like nothing more than a lost
     * speedup. `on` turns that into a refusal to initialize the VM at all.
     *
     * The training runs deliberately do *not* get this list, since they pass
     * their own `-XX:AOTMode=record` / `create`.
     */
    val consumerVmOptions: Provider<List<String>>
        get() = vmOptions.map { it + "-XX:AOTMode=on" }

    /** Class whose `main` is run to record the AOT profile. */
    abstract val trainingMainClass: Property<String>

    /** `zip-0`..`zip-9` for the jlink image. */
    abstract val compression: Property<String>

    /** jlink `--exclude-files` patterns for tools the host never launches. */
    abstract val excludeFiles: ListProperty<String>

    /**
     * Whether to run the shipped jar through ProGuard.
     *
     * This is name obfuscation, not a security boundary: the goal is only to
     * make the compiled Kotlin more tedious to read, and anyone determined can
     * still recover the extracted jar. Defaults to on. The convention plugin
     * routes the jar through ProGuard either way — see [ProGuardTask] — so
     * flipping this off produces the same `dist/lib/app.jar` layout, just
     * un-renamed.
     */
    abstract val obfuscate: Property<Boolean>

    /**
     * The ProGuard configuration file (keep rules).
     *
     * Defaults to `proguard-rules.pro` beside the build script. Its job is to
     * exempt the closed set of names the Rust host and the JVM launcher resolve
     * by string — the JNI bridge, the `@KitsuneEntrypoint` class, the AOT
     * training main — from renaming; everything else is fair game.
     */
    abstract val obfuscationRules: RegularFileProperty

    /**
     * The `com.guardsquare:proguard-base` version to run.
     *
     * Pinned here rather than in the plugin so a bump for a newer class-file
     * version — ProGuard has to be able to *read* the bytecode the toolchain
     * emits — is a one-line change in the `kitsune { }` block.
     */
    abstract val proguardVersion: Property<String>
}
