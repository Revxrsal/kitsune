//! Unpacks the image embedded by build.rs into a cache directory, once.
//!
//! Nothing here can be consumed from memory: `libjvm.so` is `dlopen`ed by path,
//! the JVM derives `java.home` from that path and opens `lib/modules` through
//! libjimage, and `-XX:AOTCache=` mmaps a real file. So the bytes have to land
//! on a filesystem, and the only question is where.
//!
//! Anywhere, as it turns out. The cache is recorded against the absolute
//! classpath in `src-kitsune/dist` (see AotCacheTask), but the JVM validates
//! classpath entry [0] — the modules image — by name only, so an image unpacked
//! under `~/.cache` still passes. The app jar at entry [1] is checked by name
//! *and* mtime, so AotCacheTask records it against a zeroed mtime and [`unpack`]
//! pins the extracted jar back to the exact epoch; otherwise the cache is
//! rejected at load time with "timestamp has changed". An image
//! unpacked under `~/.cache` still reports
//! `full module graph: enabled`, which is what makes this approach viable at
//! all: under `-XX:AOTMode=on` a rejected cache is a failed launch, not a slow
//! one.

use anyhow::{Context, Result};
use std::fs::{self, File, TryLockError};
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

/// Empty in debug builds; see build.rs.
const DIST: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/dist.tar.zst"));

/// blake3 of [`DIST`], truncated. Names the extracted image, so builds with
/// different bytes never share one and builds with identical bytes never
/// orphan one.
const HASH: &str = env!("KITSUNE_DIST_HASH");

/// Cheap on every run but the first: one `stat`, a lock, and a readdir.
pub fn extract() -> Result<PathBuf> {
    let cache = cache_root()?;
    fs::create_dir_all(&cache).with_context(|| format!("cannot create {}", cache.display()))?;

    // Taken before the existence check, not after, so a sweep cannot land
    // between "extracted" and "locked" and collect a live image.
    hold_lock(&cache)?;

    let root = cache.join(HASH);
    if !root.join("runtime").is_dir() {
        install(&cache, &root)?;
    }

    // Disk is a worse thing to lose than a launch.
    if let Err(e) = sweep(&cache)
        && super::logging()
    {
        eprintln!("kitsune: could not sweep old runtimes: {e:#}");
    }

    Ok(root)
}

fn install(cache: &Path, root: &Path) -> Result<()> {
    // Unpacking beside the target rather than into it means a killed run leaves
    // a stray staging directory, not a half-image that later runs accept.
    let staging = cache.join(format!(".staging.{HASH}.{}", std::process::id()));
    let _ = fs::remove_dir_all(&staging);
    if let Err(e) = unpack(&staging) {
        let _ = fs::remove_dir_all(&staging);
        return Err(e);
    }

    // Atomic, so concurrent first runs cannot observe a partial image. Whoever
    // loses the race deletes their copy; the two are identical by construction.
    match fs::rename(&staging, root) {
        Ok(()) => Ok(()),
        Err(e) => {
            let _ = fs::remove_dir_all(&staging);
            if root.join("runtime").is_dir() {
                Ok(())
            } else {
                Err(e).with_context(|| format!("cannot install runtime at {}", root.display()))
            }
        }
    }
}

fn unpack(staging: &Path) -> Result<()> {
    fs::create_dir_all(staging).with_context(|| format!("cannot create {}", staging.display()))?;

    let decoder = zstd::Decoder::new(DIST).context("bundled runtime is not valid zstd")?;
    let mut archive = tar::Archive::new(decoder);
    // The executable bit on `runtime/lib/jspawnhelper` lives in the tar header
    // and nowhere else; without it the JVM cannot spawn processes.
    archive.set_preserve_permissions(true);
    archive.set_preserve_mtime(true);
    archive.set_overwrite(true);
    archive
        .unpack(staging)
        .with_context(|| format!("cannot unpack bundled runtime into {}", staging.display()))?;

    // The AOT cache is recorded against app.jar's mtime, which AotCacheTask
    // zeroes so it can be reproduced here. tar's mtime round trip is not exact,
    // though — a zeroed entry comes back out one second past the epoch — and
    // under -XX:AOTMode=on even that one-second drift is a fatal "shared class
    // paths mismatch", not a soft downgrade. Pin the jar to the exact epoch the
    // cache expects rather than trusting the archive to carry it through.
    let jar = staging.join("lib/app.jar");
    File::options()
        .write(true)
        .open(&jar)
        .and_then(|f| f.set_modified(std::time::SystemTime::UNIX_EPOCH))
        .with_context(|| format!("cannot normalize the mtime of {}", jar.display()))?;
    Ok(())
}

/// Deletes every extracted image except this build's.
///
/// Guarded by a lock rather than an age heuristic, because an upgrade replaces
/// the binary while the previous version may still be running — and that
/// process opens files from its image long after startup (`jspawnhelper` on the
/// first `ProcessBuilder`, resources through jrt-fs). A lock that will not
/// budge means someone is still in there; leave it for the next launch.
fn sweep(cache: &Path) -> Result<()> {
    for entry in fs::read_dir(cache).with_context(|| format!("cannot read {}", cache.display()))? {
        let path = entry?.path();
        let Some(name) = path.file_name().and_then(|n| n.to_str()) else {
            continue;
        };
        // Staging directories belong to whoever is extracting into them.
        if !path.is_dir() || name == HASH || name.starts_with(".staging.") {
            continue;
        }

        let lock = lock_path(cache, name);
        let Ok(file) = File::create(&lock) else {
            continue;
        };
        match file.try_lock() {
            Ok(()) => {
                let _ = fs::remove_dir_all(&path);
                // Unlinking the lock with what it guards is safe: a process that
                // recreates it by name finds no image behind it and re-extracts.
                drop(file);
                let _ = fs::remove_file(&lock);
            }
            // In use, or locks are unsupported here. Either way, not ours.
            Err(TryLockError::WouldBlock | TryLockError::Error(_)) => {}
        }
    }
    Ok(())
}

/// Held until the process exits, so a newer build cannot delete this image out
/// from under a mapped libjvm. Named beside the image rather than inside it, so
/// the lock exists before the directory it guards does.
fn hold_lock(cache: &Path) -> Result<()> {
    static HELD: OnceLock<File> = OnceLock::new();

    let path = lock_path(cache, HASH);
    let file = File::create(&path).with_context(|| format!("cannot create {}", path.display()))?;
    file.lock_shared()
        .with_context(|| format!("cannot lock {}", path.display()))?;
    let _ = HELD.set(file);
    Ok(())
}

fn lock_path(cache: &Path, hash: &str) -> PathBuf {
    cache.join(format!("{hash}.lock"))
}

fn cache_root() -> Result<PathBuf> {
    let base = dirs::cache_dir().context("no cache directory for this platform")?;
    Ok(base.join("kitsune").join("runtime"))
}
