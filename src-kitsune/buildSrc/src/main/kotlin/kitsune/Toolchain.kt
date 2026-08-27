package kitsune

import org.gradle.jvm.toolchain.JavaLauncher

/**
 * Locating JDK tools through the resolved [JavaLauncher] rather than through a
 * path string handed down from the build script.
 *
 * The difference is in the up-to-date check. A `Property<String>` holding
 * `.../bin/jlink` is an `@Input` that only changes when the JDK *moves*; a
 * `JavaLauncher` declared `@Nested` contributes the installation's vendor and
 * version instead, so a JDK updated in place at a stable path (`/opt/java/openjdk`
 * in a CI image, an sdkman `current` symlink, a JAVA_HOME junction) invalidates
 * the tasks that ran its tools.
 *
 * That is the case worth catching here: an AOT cache is valid only for the exact
 * JVM build that produced it, so a runtime image left over from the previous JDK
 * is the one kind of staleness this pipeline cannot absorb. The path dropping
 * out of the fingerprint is a bonus: relocating an unchanged JDK no longer
 * forces a relink.
 */

/** Absolute path to `bin/<name>` in the launcher's JDK. */
internal fun JavaLauncher.tool(name: String): String =
    metadata.installationPath.file("bin/$name").asFile.absolutePath

/** Absolute path to the JDK's `jmods` directory, which is jlink's module path. */
internal val JavaLauncher.jmods: String
    get() = metadata.installationPath.dir("jmods").asFile.absolutePath

/**
 * The resolved JDK's exact build: `25.0.1+8-LTS`, not just `25`.
 *
 * Declaring a [JavaLauncher] `@Nested` is the documented way to get a toolchain
 * into an up-to-date check, but what it actually contributes is the *requested*
 * spec (`languageVersion`, `vendor`, `implementation`) plus the resolved major
 * version. Nothing in there separates 25.0.1 from 25.0.2, so a patch-level
 * upgrade in place would leave the tasks reporting up-to-date.
 *
 * A stale image is self-consistent rather than broken (the AOT cache is trained
 * against whatever image is on disk, so the two never disagree at startup), but
 * it does mean upgrading the JDK would silently not change what ships. Pairing
 * this with the `@Nested` launcher closes that.
 */
internal val JavaLauncher.buildId: String
    get() = "${metadata.vendor} ${metadata.javaRuntimeVersion}"
