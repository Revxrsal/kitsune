package kitsune

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/** Configuration for the `kitsune.jlink` convention plugin. */
abstract class KitsuneExtension {

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
}
