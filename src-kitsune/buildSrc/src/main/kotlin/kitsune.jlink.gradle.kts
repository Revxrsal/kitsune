import kitsune.AotCacheTask
import kitsune.JdepsModulesTask
import kitsune.JlinkTask
import kitsune.KitsuneExtension
import kitsune.ProGuardTask
import kitsune.VerifyAotCacheTask
import kitsune.WriteVmOptionsTask
import org.gradle.jvm.tasks.Jar

plugins {
    java
}

val kitsune = extensions.create<KitsuneExtension>("kitsune").apply {
    compression.convention("zip-9")
    // Right whenever the bindings file sits next to Bridge.ts, which is where a
    // frontend that did not say otherwise will have put it.
    bridgeImport.convention("./Bridge")
    // Files the host never reaches: it loads libjvm.so directly rather than
    // shelling out to a JDK tool, and the AOT cache replaces CDS.
    //   keytool   - a JDK tool; nothing launches it
    //   classlist - input to `-Xshare:dump`, which the AOT workflow supersedes
    //   jexec     - binfmt_misc helper for running jars from the kernel
    // Worth ~120 KB out of ~42 MB. lib/jrt-fs.jar is deliberately *not* here:
    // it is only ~110 KB and classpath-scanning libraries read the runtime
    // image through the jrt filesystem.
    excludeFiles.convention(
        listOf(
            "/java.base/bin/keytool",
            "/java.base/lib/classlist",
            "/java.base/lib/jexec",
        )
    )
    proguardVersion.convention("7.10.0")
}

// Set outside the apply block so `layout` and `providers` resolve against the
// project rather than the extension receiver.
kitsune.obfuscationRules.convention(layout.projectDirectory.file("proguard-rules.pro"))

// `-Pkitsune.obfuscate=false` is how a build that is not going to ship opts out
// of the ProGuard pass; src-tauri/build.rs passes it on every non-embedding
// (debug) build, which is where the pass is pure cost. Shipping stays the
// default, so a bare `./gradlew dist` and anything driving `assemble` still
// obfuscate without being told to.
//
// A convention rather than a set(), so the layering reads explicit `kitsune {
// obfuscate.set(..) }` > -P > on. Someone who pins it in the build script means
// it, including for debug builds.
//
// Parsed strictly rather than with String.toBoolean(), which maps every typo to
// false: the failure that would hide is a release that silently shipped
// un-renamed, which is the one worth being loud about.
kitsune.obfuscate.convention(
    providers.gradleProperty("kitsune.obfuscate").map {
        when (it) {
            "true" -> true
            "false" -> false
            else -> error("-Pkitsune.obfuscate expects true or false, got '$it'")
        }
    }.orElse(true)
)

// jdeps/jlink are taken from the project's Java toolchain, not from PATH, so
// the tools always match the version the code is compiled against.
//
// Handed to the tasks as the launcher itself rather than as a path, because the
// tasks declare it @Nested: what they key on is then the JDK's vendor and
// version, not where it happens to be installed. A JDK upgraded in place at a
// stable path is the case that matters; see kitsune.tool.
val javaLauncher = extensions.getByType<JavaToolchainService>()
    .launcherFor(java.toolchain)

// Only pays off on JDKs that ship unstripped binaries; Temurin does not, so this
// is usually a no-op. Skipped entirely when objcopy is not installed.
val objcopy = providers.provider {
    System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it, "objcopy") }
        .firstOrNull { it.canExecute() }
        ?.absolutePath
}

val distDir = layout.projectDirectory.dir("dist")
val shadowJar = tasks.named<Jar>("shadowJar")

// ProGuard, resolved from the project's repositories at execution time. The
// version comes from the extension, deferred so a `kitsune { }` override still
// wins even though this line runs before that block.
val proguardTool = configurations.create("proguardTool") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies.addProvider(
    proguardTool.name,
    kitsune.proguardVersion.map { "com.guardsquare:proguard-base:$it" }
)

// The obfuscated (or, when disabled, copied) jar the app ships, and the sole
// producer of dist/lib/app.jar. Everything that used to read the shadow jar as
// "the app jar" reads this instead, so the shipped bytes are always the ones
// that went through here. jdeps stays on the shadow jar deliberately: it only
// needs the JDK-module set, which renaming does not move, and keeping it off
// this task lets jlink run without waiting for ProGuard.
val obfuscate = tasks.register<ProGuardTask>("obfuscate") {
    group = "distribution"
    description = "Runs the shadow jar through ProGuard into dist/lib/app.jar."

    inputJar.set(shadowJar.flatMap { it.archiveFile })
    rulesFile.set(kitsune.obfuscationRules)
    obfuscating.set(kitsune.obfuscate)
    proguardClasspath.from(proguardTool)
    launcher.set(javaLauncher)
    outputJar.set(distDir.file("lib/app.jar"))
    // Persistent across `clean`, so the JEP-493 module extraction is paid once
    // per JDK rather than per clean build. Only used when the JDK lacks jmods.
    jdkModulesCache.set(
        layout.dir(provider { gradle.gradleUserHomeDir.resolve("caches/kitsune/jdk-modules") })
    )
}
val appJar = obfuscate.flatMap { it.outputJar }

val jdepsModules = tasks.register<JdepsModulesTask>("jdepsModules") {
    group = "distribution"
    description = "Prints the JDK modules required by the shadow jar."

    jar.set(shadowJar.flatMap { it.archiveFile })
    multiRelease.set(java.toolchain.languageVersion.map { it.toString() })
    launcher.set(javaLauncher)
    modulesFile.set(layout.buildDirectory.file("jdeps/modules.txt"))
}

val jlinkRuntime = tasks.register<JlinkTask>("jlinkRuntime") {
    group = "distribution"
    description = "Builds a trimmed JRE into dist/runtime using jlink."

    modulesFile.set(jdepsModules.flatMap { it.modulesFile })
    launcher.set(javaLauncher)
    compression.set(kitsune.compression)
    vmVariant.set("server")
    objcopyExecutable.set(objcopy)
    excludeFiles.set(kitsune.excludeFiles)
    outputDir.set(distDir.dir("runtime"))
}

val writeVmOptions = tasks.register<WriteVmOptionsTask>("writeVmOptions") {
    group = "distribution"
    description = "Writes the VM flags shared by the AOT cache and the host process."

    // The host reads this file verbatim, so AOTMode=on ships with it and the
    // host needs no knowledge of the flag.
    vmOptions.set(kitsune.consumerVmOptions)
    optionsFile.set(distDir.file("lib/vmoptions.txt"))
}

val aotCache = tasks.register<AotCacheTask>("aotCache") {
    group = "distribution"
    description = "Records an AOT cache (JEP 483/515) using the bundled runtime."

    runtimeDir.set(jlinkRuntime.flatMap { it.outputDir })
    // The obfuscated jar, not the shadow jar: the cache records classes from and
    // is validated against the exact jar the host loads, which is this one.
    jar.set(appJar)
    trainingMainClass.set(kitsune.trainingMainClass)
    vmOptions.set(kitsune.vmOptions)
    configurationFile.set(layout.buildDirectory.file("aot/app.aotconf"))
    cacheFile.set(distDir.file("lib/app.aot"))
}

val verifyAotCache = tasks.register<VerifyAotCacheTask>("verifyAotCache") {
    group = "verification"
    description = "Fails the build if the AOT cache does not actually engage."

    runtimeDir.set(jlinkRuntime.flatMap { it.outputDir })
    jar.set(aotCache.flatMap { it.jar })
    cacheFile.set(aotCache.flatMap { it.cacheFile })
    trainingMainClass.set(kitsune.trainingMainClass)
    // Exactly what the host will pass, AOTMode=on included.
    vmOptions.set(kitsune.consumerVmOptions)
}

val dist = tasks.register("dist") {
    group = "distribution"
    description = "Assembles dist/: trimmed runtime, jar, AOT cache, VM options."
    dependsOn(jlinkRuntime, writeVmOptions, aotCache, verifyAotCache)
}

// The AOT cache records the classpath it was trained on, so it has to be built
// against the jar at the path the host will actually load.
//
// That path is dist/lib/app.jar, and the `obfuscate` task is its one producer.
// The shadow jar is now an intermediate that lands in the default build/libs
// and is never loaded by anyone. That keeps the drift the old layout guarded
// against unrepresentable: there is a single dist/lib/app.jar, and a bare
// `gradlew shadowJar` or an IDE build still ends at it, because the finalizer
// below pulls the whole chain (obfuscate -> aotCache) through on every jar
// rebuild.
shadowJar {
    // Rebuilding the jar without rebuilding the cache is not a lost speedup;
    // it silently runs the OLD code. The JVM does not protect you here: with
    // -Xlog:class+path=trace it records the app jar as classpath entry [1] and
    // then validates only entry [0], the modules image. A changed app jar passes
    // unnoticed and its stale archived classes are served in preference to the
    // ones on disk. -XX:+VerifySharedSpaces does not help; it checksums the
    // archive, not the classpath.
    //
    // So the cache is regenerated by the act of building the jar, rather than by
    // remembering to run another task afterwards.
    finalizedBy(dist)
}

// The AOT cache is not an optional post-build step. The host runs with
// -XX:AOTMode=on and will not start without a valid cache, so a build that did
// not produce one is not a build, hence `assemble` rather than a task you
// remember to run.
//
// Changing kitsune.vmOptions re-records the cache rather than breaking it, so
// verifyAotCache is not there to catch drift between the two lists; that is
// what generating both from one source already prevents. It catches the case
// where the cache is built successfully and still does not engage: a flag
// combination that leaves aot-linking disabled, a toolchain bump that
// invalidates the image, a hand-edited dist/. Wiring it into `check` keeps that
// assertion running on every build rather than only when someone asks for it.
tasks.named("assemble") { dependsOn(dist) }
tasks.named("check") { dependsOn(verifyAotCache) }

// dist/ is build output like build/ is, now that the jar lands there directly.
tasks.named<Delete>("clean") { delete(distDir) }
