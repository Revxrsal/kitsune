import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // No version: the root build already puts the Kotlin plugin of the pinned
    // version on the build classpath, and declaring a second version here is
    // what produces "plugin already on the classpath with a different version".
    kotlin("jvm")
}

// Subprojects do not inherit the root project's repositories.
repositories {
    mavenCentral()
}

dependencies {
    // KSP 2.x is versioned independently of Kotlin: 2.3.11 is a KSP version,
    // not a Kotlin one. KSP2 runs the annotation processing pass on its own
    // embedded Analysis API rather than on the compiler the project builds
    // with, which is what lets this pair with Kotlin 2.4.10.
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.11")

    // kotlinpoet-ksp is the interop layer: KSType/KSClassDeclaration -> TypeName
    // and FileSpec.writeTo(CodeGenerator), so generated files are registered
    // with KSP's incremental machinery instead of written behind its back.
    implementation("com.squareup:kotlinpoet:2.3.0")
    implementation("com.squareup:kotlinpoet-ksp:2.3.0")
}

kotlin {
    // Compiled with the same JDK as the rest of the build...
    jvmToolchain(25)

    compilerOptions {
        // ...but emitting bytecode the *Gradle daemon* can load. KSP does not
        // run the processor on the toolchain it compiles :app with; it loads it
        // into a worker inside the daemon JVM, so class file version 69 here
        // fails the build with "compiled by a more recent version of the Java
        // Runtime" before a single symbol is processed.
        //
        // -Xjdk-release does the other half: without it this compiles against
        // the JDK 25 API and only discovers a 25-only reference at processing
        // time, on someone else's machine.
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

// The module has no Java sources, but the Kotlin plugin still cross-checks the
// two tasks' targets and refuses the mismatch above, so javac has to be told the
// same thing.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
