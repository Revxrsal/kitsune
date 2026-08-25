import kitsune.AotCacheTask
import kitsune.JdepsModulesTask
import kitsune.JlinkTask
import kitsune.KitsuneExtension
import kitsune.VerifyAotCacheTask
import kitsune.WriteVmOptionsTask
import org.gradle.jvm.tasks.Jar

plugins {
    java
}

val kitsune = extensions.create<KitsuneExtension>("kitsune").apply {
    compression.convention("zip-9")
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
}

// jdeps/jlink are taken from the project's Java toolchain, not from PATH, so
// the tools always match the version the code is compiled against.
val toolchainHome = extensions.getByType<JavaToolchainService>()
    .launcherFor(java.toolchain)
    .map { it.metadata.installationPath.asFile.absolutePath }

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

val jdepsModules = tasks.register<JdepsModulesTask>("jdepsModules") {
    group = "distribution"
    description = "Prints the JDK modules required by the shadow jar."

    jar.set(shadowJar.flatMap { it.archiveFile })
    multiRelease.set(java.toolchain.languageVersion.map { it.toString() })
    jdepsExecutable.set(toolchainHome.map { "$it/bin/jdeps" })
    modulesFile.set(layout.buildDirectory.file("jdeps/modules.txt"))
}

val jlinkRuntime = tasks.register<JlinkTask>("jlinkRuntime") {
    group = "distribution"
    description = "Builds a trimmed JRE into dist/runtime using jlink."

    modulesFile.set(jdepsModules.flatMap { it.modulesFile })
    jlinkExecutable.set(toolchainHome.map { "$it/bin/jlink" })
    jmodsPath.set(toolchainHome.map { "$it/jmods" })
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
    jar.set(shadowJar.flatMap { it.archiveFile })
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
    description = "Assembles dist/ — trimmed runtime, jar, AOT cache, VM options."
    dependsOn(jlinkRuntime, writeVmOptions, aotCache, verifyAotCache)
}

// The AOT cache records the classpath it was trained on, so it has to be built
// against the jar at the path the host will actually load.
//
// That path is dist/lib, and the jar is *built* there rather than copied there.
// An earlier version copied build/libs/app.jar into dist/lib as part of `dist`,
// which left two locations for one artifact: a bare `gradlew shadowJar` — or any
// IDE build — refreshed build/libs and left dist/lib behind, and the host reads
// dist/lib, so it silently ran stale code. One output directory makes that drift
// unrepresentable.
shadowJar {
    destinationDirectory.set(distDir.dir("lib"))

    // Rebuilding the jar without rebuilding the cache is not a lost speedup —
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
// not produce one is not a build — hence `assemble`, not a task you remember to
// run.
//
// Changing kitsune.vmOptions re-records the cache rather than breaking it, so
// verifyAotCache is not there to catch drift between the two lists — that is
// what generating both from one source already prevents. It catches the case
// where the cache is built successfully and still does not engage: a flag
// combination that leaves aot-linking disabled, a toolchain bump that
// invalidates the image, a hand-edited dist/. Wiring it into `check` keeps that
// assertion running on every build rather than only when someone asks for it.
tasks.named("assemble") { dependsOn(dist) }
tasks.named("check") { dependsOn(verifyAotCache) }

// dist/ is build output like build/ is, now that the jar lands there directly.
tasks.named<Delete>("clean") { delete(distDir) }
