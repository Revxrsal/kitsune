//! The application image the JVM is started against:
//!
//! ```text
//! <root>/runtime/           jlink image
//! <root>/lib/app.jar        shadow jar
//! <root>/lib/app.aot        AOT cache (JEP 483/515)
//! <root>/lib/vmoptions.txt  VM flags the cache was recorded with
//! ```

use anyhow::{Context, Result, bail};
use std::path::PathBuf;

/// Points a build at a hand-assembled image instead of the one it would find
/// on its own.
const ROOT_ENV: &str = "KITSUNE_APP_ROOT";

pub struct JavaDist {
    root: PathBuf,
}

impl JavaDist {
    /// Release builds unpack the image embedded in the binary; debug builds read
    /// `src-kitsune/dist` in place, so iterating on the Kotlin half does not also
    /// mean recompressing 54 MB.
    pub fn locate() -> Result<Self> {
        let root = match std::env::var_os(ROOT_ENV) {
            Some(root) => PathBuf::from(root),
            None => default_root()?,
        };

        let image = Self { root };
        for (what, path) in [
            ("JVM", image.libjvm()),
            ("application jar", image.jar()),
            // Required, not an optimization: vmoptions.txt sets -XX:AOTMode=on,
            // so a missing cache aborts VM startup. Checked here only to beat
            // the JVM to the error message, since "Error occurred during
            // initialization of VM" says nothing useful.
            ("AOT cache", image.aot_cache()),
        ] {
            if !path.exists() {
                bail!(
                    "no {what} at {}; run ./gradlew dist in src-kitsune/",
                    path.display()
                );
            }
        }
        Ok(image)
    }

    /// Platform-specific path to the JVM shared library inside the jlink image.
    pub fn libjvm(&self) -> PathBuf {
        let rt = self.root.join("runtime");
        if cfg!(target_os = "windows") {
            rt.join("bin/server/jvm.dll")
        } else if cfg!(target_os = "macos") {
            rt.join("lib/server/libjvm.dylib")
        } else {
            rt.join("lib/server/libjvm.so")
        }
    }

    pub fn jar(&self) -> PathBuf {
        self.root.join("lib/app.jar")
    }

    pub fn aot_cache(&self) -> PathBuf {
        self.root.join("lib/app.aot")
    }

    /// Flags shared with the AOT training runs, read from the file Gradle
    /// generates.
    ///
    /// These are not cosmetic. The JVM validates them against the cache at
    /// startup and several mismatches reject it outright rather than degrading:
    /// `-XX:+UseCompactObjectHeaders` must agree exactly, and passing
    /// `--enable-native-access=ALL-UNNAMED` here when the cache was recorded
    /// without it disables the archived module graph, which invalidates every
    /// aot-linked class in the cache. Keeping one generated list on both sides
    /// is what makes that unable to drift.
    ///
    /// The file also carries `-XX:AOTMode=on`, so anything that does slip
    /// through aborts VM startup instead of quietly costing 250 ms per launch.
    pub fn vm_options(&self) -> Result<Vec<String>> {
        let path = self.root.join("lib/vmoptions.txt");
        let text = std::fs::read_to_string(&path)
            .with_context(|| format!("cannot read {}", path.display()))?;
        Ok(text
            .lines()
            .map(str::trim)
            .filter(|line| !line.is_empty() && !line.starts_with('#'))
            .map(str::to_owned)
            .collect())
    }
}

#[cfg(embedded_runtime)]
fn default_root() -> Result<PathBuf> {
    super::bundle::extract()
}

#[cfg(not(embedded_runtime))]
fn default_root() -> Result<PathBuf> {
    let exe = std::env::current_exe()?;
    let beside_exe = exe.parent().unwrap();
    if beside_exe.join("runtime").is_dir() {
        return Ok(beside_exe.to_path_buf());
    }

    // CARGO_MANIFEST_DIR is src-tauri/; the Gradle build is its sibling.
    let dev = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .join("src-kitsune/dist");
    if dev.join("runtime").is_dir() {
        return Ok(dev);
    }
    bail!("no runtime found; run ./gradlew dist in src-kitsune/ first")
}
