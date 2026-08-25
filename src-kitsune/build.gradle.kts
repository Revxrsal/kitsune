plugins {
    kotlin("jvm") version "2.4.10"
    id("com.gradleup.shadow") version "8.3.6"
    kotlin("plugin.serialization") version "2.4.10"

    // Runs :codegen over this module's sources. KSP 2.x is versioned
    // independently of Kotlin — 2.3.11 is a KSP version, not a Kotlin one — and
    // must match the symbol-processing-api :codegen compiles against.
    id("com.google.devtools.ksp") version "2.3.11"

    id("kitsune.jlink")
}

group = "revxrsal"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.11.0")

    // `suspend` itself is stdlib, but the scope suspending exports are launched
    // in — and the dispatchers behind it — are not. See ipc/Scope.kt.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // `ksp`, not `implementation`: the processor runs at compile time and must
    // stay off the runtime classpath — it drags in kotlinpoet and the KSP API,
    // neither of which the shipped jar has any use for.
    ksp(project(":codegen"))
}

kotlin {
    jvmToolchain(25)
}

tasks.shadowJar {
    archiveBaseName.set("app")
    archiveClassifier.set("")   // no "-all" suffix
    archiveVersion.set("")      // no version in filename
}

kitsune {
    // Generated TypeScript for every export, written into the frontend's source
    // tree next to Bridge.ts — which is what makes the default ./Bridge import
    // resolve, and why bridgeImport is left alone here.
    bindings.set(layout.projectDirectory.file("../src/bindings.ts"))

    // The Rust host's handover into Kotlin, generated from whichever class
    // carries @KitsuneEntrypoint. Overwritten on every build that moves it.
    entrypoint.set(layout.projectDirectory.file("../src-tauri/src/jvm/entrypoint.rs"))

    // Not MainKt: the training run has no Rust host attached, so it must not
    // invoke Bridge's external funs. See Training.kt.
    trainingMainClass.set("revxrsal.kitsune.Training")

    // Flags shared by the AOT training runs and the Rust host. Changing this
    // list rebuilds the cache; the host picks it up from dist/lib/vmoptions.txt
    // with no recompile. Every entry here is validated against the cache at
    // startup, and several of them reject it outright on mismatch — see the
    // docs on AotCacheTask.
    vmOptions.set(
        listOf(
            // JEP 519. Object headers shrink from 12 bytes to 8; a product
            // feature in 25 but still opt-in. Must match the cache exactly.
            "-XX:+UseCompactObjectHeaders",
            // Desktop workload: fewer threads and lower RSS than G1, which is
            // tuned for heaps far larger than this app will ever hold.
            "-XX:+UseSerialGC",
            // The default is a fraction of physical RAM, which reserves absurd
            // address space on a workstation. Also recorded into the cache.
            "-Xmx512m",
            // JEP 472. Silences the restricted-method warnings a JDBC driver
            // will trigger later, and future-proofs against them becoming hard
            // errors. Omitting it here while the host passes it makes the cache
            // unusable, so it belongs in the shared list even today.
            "--enable-native-access=ALL-UNNAMED",
        )
    )
}

// A KSP processor is configured only through processor options, so what the
// kitsune { } block above says about bindings is forwarded to TypeScriptProcessor
// here. Both are passed as providers: the block is evaluated after this one, and
// `arg(key, Provider)` defers the read until KSP actually runs, so the order the
// two appear in this file does not decide what the processor sees.
//
// The empty fallback is how "generate nothing" is expressed. A MapProperty entry
// backed by a provider with no value fails the build when KSP reads its options,
// which would make an unset `bindings` an error rather than the opt-out it is.
ksp {
    arg("kitsune.bindings", kitsune.bindings.map { it.asFile.absolutePath }.orElse(""))
    arg("kitsune.bridgeImport", kitsune.bridgeImport)
    arg("kitsune.entrypoint", kitsune.entrypoint.map { it.asFile.absolutePath }.orElse(""))
}
