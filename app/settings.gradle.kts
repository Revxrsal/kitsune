plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "app"

// The KSP processor that reads @ExportFunction / @ExportEvent / @Listener and
// writes the registry the Rust host dispatches through. It has to be a separate
// module: a processor is a *compiler* dependency of the module it processes, so
// it must already be compiled before :app's own compilation starts.
include(":codegen")
