mod dist;
pub mod entrypoint;

#[cfg(embedded_runtime)]
mod bundle;

pub use dist::JavaDist;

use anyhow::Result;
use jni::{InitArgsBuilder, JNIVersion, JavaVM};

/// Set to enable `-Xlog:aot` and `-Xcheck:jni`.
const LOG_ENV: &str = "KITSUNE_JVM_LOG";

fn logging() -> bool {
    std::env::var_os(LOG_ENV).is_some()
}

/// Starts the JVM against `image` and hands control to the Kotlin entry point.
pub fn run(image: &JavaDist) -> Result<JavaVM> {
    let mut args = InitArgsBuilder::new().version(JNIVersion::V1_8);
    for option in image.vm_options()? {
        args = args.option(option);
    }
    // Absolute, matching the path the cache was trained against.
    args = args.option(format!("-Djava.class.path={}", image.jar().display()));
    args = args.option(format!("-XX:AOTCache={}", image.aot_cache().display()));
    if logging() {
        args = args.option("-Xlog:aot=info");
        // Catches misuse of the JNI API; costs real throughput, so it stays off
        // unless asked for.
        args = args.option("-Xcheck:jni");
    }

    let libjvm = image.libjvm();
    Ok(JavaVM::with_libjvm(args.build()?, || Ok(libjvm))?)
}
