//! Packs `app/dist` — the jlink image, the shadow jar, the AOT cache and the
//! shared VM options — into a single zstd-compressed tarball that gets embedded
//! in the binary with `include_bytes!`.
//!
//! Only release builds embed. A debug build writes an empty blob and leaves
//! `embedded_runtime` unset, so `app_root()` keeps reading `app/dist` straight
//! off disk and `cargo build` stays fast while iterating on the Kotlin side.
//!
//! The tar is built deterministically — sorted entries, zeroed mtime/uid/gid,
//! mode normalised to 0644/0755 — so an unchanged `dist/` hashes to the same
//! value across rebuilds. That hash names the extraction directory, so a
//! rebuild that changes nothing does not orphan the already-extracted image.

use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Write};
use std::path::{Path, PathBuf};

/// Slow enough to notice (~13 s on the 54 MB image) and worth ~1.5 MB over
/// level 12. build.rs only reruns when `dist/` actually changes, so that cost
/// lands on the builds that produced new bytes anyway. Override with
/// `KITSUNE_ZSTD_LEVEL` when bisecting something and the wait is in the way.
const DEFAULT_ZSTD_LEVEL: i32 = 19;

fn main() {
    let manifest = PathBuf::from(std::env::var_os("CARGO_MANIFEST_DIR").unwrap());
    let dist = manifest.join("app").join("dist");
    let out = PathBuf::from(std::env::var_os("OUT_DIR").unwrap());
    let blob = out.join("dist.tar.zst");

    println!("cargo::rerun-if-changed={}", dist.display());
    println!("cargo::rerun-if-env-changed=KITSUNE_EMBED");
    println!("cargo::rerun-if-env-changed=KITSUNE_ZSTD_LEVEL");
    println!("cargo::rustc-check-cfg=cfg(embedded_runtime)");

    if !should_embed() {
        // include_bytes! still has to resolve, so the file has to exist.
        fs::write(&blob, []).unwrap();
        println!("cargo::rustc-env=KITSUNE_DIST_HASH=dev");
        return;
    }

    assert!(
        dist.join("runtime").is_dir() && dist.join("lib").join("app.jar").is_file(),
        "no bundled runtime at {} — run ./gradlew dist in app/ before a release build",
        dist.display()
    );

    let level = std::env::var("KITSUNE_ZSTD_LEVEL")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(DEFAULT_ZSTD_LEVEL);

    let file = BufWriter::new(File::create(&blob).unwrap());
    let mut encoder = zstd::Encoder::new(file, level).unwrap();
    encoder.include_checksum(true).unwrap();
    {
        let mut tar = tar::Builder::new(&mut encoder);
        append_tree(&mut tar, &dist, Path::new(""));
        tar.finish().unwrap();
    }
    encoder.finish().unwrap().flush().unwrap();

    println!("cargo::rustc-env=KITSUNE_DIST_HASH={}", hash(&blob));
    println!("cargo::rustc-cfg=embedded_runtime");
    tauri_build::build()
}

fn should_embed() -> bool {
    match std::env::var("KITSUNE_EMBED").as_deref() {
        Ok("1") => true,
        Ok("0") => false,
        _ => std::env::var("PROFILE").as_deref() == Ok("release"),
    }
}

/// Appends `dir` under `prefix`, sorted, with every field that varies between
/// two builds of identical content flattened out.
fn append_tree<W: Write>(tar: &mut tar::Builder<W>, dir: &Path, prefix: &Path) {
    let mut entries: Vec<_> = fs::read_dir(dir)
        .unwrap_or_else(|e| panic!("cannot read {}: {e}", dir.display()))
        .map(|e| e.unwrap().path())
        .collect();
    entries.sort();

    for path in entries {
        let name = prefix.join(path.file_name().unwrap());
        let meta = fs::symlink_metadata(&path).unwrap();

        if meta.is_dir() {
            append_tree(tar, &path, &name);
        } else if meta.is_file() {
            let mut header = tar::Header::new_gnu();
            header.set_size(meta.len());
            header.set_mode(mode_of(&meta));
            header.set_mtime(0);
            header.set_uid(0);
            header.set_gid(0);
            header.set_entry_type(tar::EntryType::Regular);
            let mut reader = BufReader::new(File::open(&path).unwrap());
            tar.append_data(&mut header, &name, &mut reader).unwrap();
        } else {
            // jlink emits neither symlinks nor devices; if that ever changes,
            // silently dropping the entry would produce a runtime image that is
            // subtly wrong rather than obviously broken.
            panic!("unsupported file type in dist: {}", path.display());
        }
    }
}

/// The only bit that has to survive the round trip is the executable one —
/// `runtime/lib/jspawnhelper` is exec'd by the JVM whenever the app spawns a
/// process, and a non-executable copy fails deep inside ProcessBuilder.
#[cfg(unix)]
fn mode_of(meta: &fs::Metadata) -> u32 {
    use std::os::unix::fs::MetadataExt;
    if meta.mode() & 0o111 != 0 { 0o755 } else { 0o644 }
}

#[cfg(not(unix))]
fn mode_of(_meta: &fs::Metadata) -> u32 {
    0o644
}

fn hash(path: &Path) -> String {
    let bytes = fs::read(path).unwrap();
    blake3::hash(&bytes).to_hex()[..32].to_owned()
}
