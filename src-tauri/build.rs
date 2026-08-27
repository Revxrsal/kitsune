//! Builds the Kotlin half of the app and packs `src-kitsune/dist` (the jlink
//! image, the shadow jar, the AOT cache and the shared VM options) into a
//! single zstd-compressed tarball that gets embedded in the binary with
//! `include_bytes!`.
//!
//! `./gradlew shadowJar` runs first, on every profile: a debug build does not
//! embed anything but still *reads* `src-kitsune/dist` at runtime, so both
//! halves need it fresh. It reruns only when one of `GRADLE_INPUTS` changes,
//! which is what keeps `cargo build` from paying for a Gradle up-to-date check
//! it has no reason to do.
//!
//! Only release builds embed. A debug build writes an empty blob and leaves
//! `embedded_runtime` unset, so `app_root()` keeps reading `src-kitsune/dist`
//! straight off disk and `cargo build` stays fast while iterating on the Kotlin
//! side.
//!
//! The tar is built deterministically, with sorted entries, zeroed
//! mtime/uid/gid, mode normalised to 0644/0755 and symlinks followed, so an
//! unchanged `dist/` hashes to the same value across rebuilds. That hash names
//! the extraction directory, so a rebuild that changes nothing does not orphan
//! the already-extracted image.

use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

/// The files that decide what `./gradlew shadowJar` produces. Everything else
/// under `src-kitsune/` is either build output (`build/`, `.gradle/`,
/// `.kotlin/`) or plays no part in the jar (`src/test/`), and tracking any of
/// it would rerun this script, and the recompression below, on noise.
///
/// Paths are relative to `src-kitsune/`. Directories are walked recursively by
/// Cargo.
const GRADLE_INPUTS: &[&str] = &[
    // Dependencies, plugin and Kotlin versions, the toolchain, and the
    // kitsune { } block the jlink and AOT tasks read their settings out of.
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "gradle/libs.versions.toml",
    // A wrapper bump changes the Gradle version the whole build runs on, and
    // the daemon JVM criteria decide which JDK it runs *on*; :codegen is
    // compiled to a class file version the daemon has to be able to load, so a
    // change here fails :kspKotlin before any other input gets a say.
    "gradle/wrapper/gradle-wrapper.properties",
    "gradle/gradle-daemon-jvm.properties",
    // What actually ends up in the jar. `src/test` is deliberately absent:
    // nothing in it reaches dist/, and editing a test should not cost a relink.
    "src/main",
    // The KSP processor runs during :app's compilation, so its sources are
    // compile inputs of :app exactly as :app's own sources are, and it is what
    // writes ../src/bindings.ts and src/jvm/entrypoint.rs.
    "codegen/build.gradle.kts",
    "codegen/src/main",
    // The jlink / AOT / vmoptions tasks themselves.
    "buildSrc/build.gradle.kts",
    "buildSrc/settings.gradle.kts",
    "buildSrc/src/main",
];

/// Slow enough to notice (~13 s on the 54 MB image) and worth ~1.5 MB over
/// level 12. build.rs only reruns when `dist/` actually changes, so that cost
/// lands on the builds that produced new bytes anyway. Override with
/// `KITSUNE_ZSTD_LEVEL` when bisecting something and the wait is in the way.
const DEFAULT_ZSTD_LEVEL: i32 = 19;

fn main() {
    // Unconditional: Tauri's generated context is needed by every profile, and
    // the debug path below returns early.
    tauri_build::build();

    let manifest = PathBuf::from(std::env::var_os("CARGO_MANIFEST_DIR").unwrap());
    // CARGO_MANIFEST_DIR is src-tauri/; the Gradle build is its sibling.
    let kotlin = manifest.parent().unwrap().join("src-kitsune");
    let dist = kotlin.join("dist");
    let out = PathBuf::from(std::env::var_os("OUT_DIR").unwrap());
    let blob = out.join("dist.tar.zst");

    track_gradle_inputs(&kotlin);
    // dist/ is Gradle's output, not its input, but tracking it too means a
    // hand-deleted or hand-edited image is noticed. Files Gradle writes while
    // this script runs are older than the stamp Cargo writes when it exits, so
    // this does not make the script rerun forever.
    println!("cargo::rerun-if-changed={}", dist.display());
    println!("cargo::rerun-if-env-changed=KITSUNE_EMBED");
    println!("cargo::rerun-if-env-changed=KITSUNE_ZSTD_LEVEL");
    println!("cargo::rerun-if-env-changed=KITSUNE_SKIP_GRADLE");
    println!("cargo::rustc-check-cfg=cfg(embedded_runtime)");

    // Before the debug early-return: an unembedded build still runs against
    // dist/ off disk. This also lands before rustc reads the crate. KSP writes
    // src/jvm/entrypoint.rs, and a build script finishes before compilation of
    // its own crate begins, so the file generated here is the one compiled.
    run_gradle(&kotlin, &out);

    if !should_embed() {
        // include_bytes! still has to resolve, so the file has to exist.
        fs::write(&blob, []).unwrap();
        println!("cargo::rustc-env=KITSUNE_DIST_HASH=dev");
        return;
    }

    assert!(
        dist.join("runtime").is_dir() && dist.join("lib").join("app.jar").is_file(),
        "no bundled runtime at {}; KITSUNE_SKIP_GRADLE is set, so run ./gradlew shadowJar in src-kitsune/ yourself",
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
}

fn should_embed() -> bool {
    match std::env::var("KITSUNE_EMBED").as_deref() {
        Ok("1") => true,
        Ok("0") => false,
        _ => std::env::var("PROFILE").as_deref() == Ok("release"),
    }
}

/// Emits one `rerun-if-changed` per Gradle input that is actually present.
///
/// The existence check is not defensiveness: Cargo treats a tracked path it
/// cannot stat as changed, so naming `gradle.properties` in a tree that has no
/// `gradle.properties` would rerun this script on every single build. The cost
/// is that *creating* one of the optional files is not itself a trigger; it
/// takes effect on the next rebuild that happens for any other reason.
fn track_gradle_inputs(kotlin: &Path) {
    for rel in GRADLE_INPUTS {
        let path = kotlin.join(rel);
        if path.exists() {
            println!("cargo::rerun-if-changed={}", path.display());
        }
    }
}

/// Runs `./gradlew shadowJar` in `src-kitsune/`.
///
/// `shadowJar` rather than `dist`: the jar is built straight into `dist/lib`
/// and `shadowJar` is `finalizedBy(dist)`, so this one task also pulls the
/// jlink image, the AOT cache and vmoptions.txt along, which is exactly the
/// wiring that stops a jar from landing in dist/ without the cache being
/// re-recorded against it.
///
/// `KITSUNE_SKIP_GRADLE=1` opts out, for builds that assemble `dist/` by some
/// other route: a CI stage that builds the two halves separately, or bisecting
/// the Rust side against a fixed image.
///
/// ## Why a log file and not `Command::output()`
///
/// Gradle's output has to go somewhere other than this script's stdout, which
/// is Cargo's directive stream, but it must not go into a *pipe*.
/// `output()` reads until EOF rather than until the child exits, and the
/// wrapper forks a Gradle daemon that inherits the pipe and outlives it by
/// design: the whole point of the daemon is to still be there for the next
/// build. So the wrapper exits, this script would exit, and the read blocks on
/// a write end nobody will ever close, for the length of the daemon's idle
/// timeout: three hours by default, and longer still every time another build
/// resets it. From the outside `cargo build` simply hangs, silently, with no
/// child process left to point at. It only bites the runs that *start* a
/// daemon; a run that reuses a warm one never forks and never hangs, which is
/// what makes it look intermittent.
///
/// Redirecting to a file sidesteps all of it: `status()` waits on the wrapper
/// and nothing else, an inherited file handle blocks no one, and the log is
/// still there to be read back into the panic below, and to be read by hand
/// afterwards, which a pipe never allowed.
fn run_gradle(kotlin: &Path, out: &Path) {
    if std::env::var("KITSUNE_SKIP_GRADLE").as_deref() == Ok("1") {
        return;
    }

    seal_cargo_pipes();

    // `--console=plain`: the output is redirected, not attached to a terminal,
    // and the rich console's control codes make the panic below unreadable.
    let mut args = vec!["shadowJar", "--console=plain"];

    // Nothing this build produces is going to be handed to anyone, so the
    // ProGuard pass is pure latency: it renames classes nobody will read, and
    // then the AOT cache has to be re-recorded against the renamed jar. Both
    // land on every cold Gradle daemon, which is most of them.
    //
    // Off is not a different shape of build. `obfuscate` stays the sole
    // producer of dist/lib/app.jar and copies the shadow jar through unchanged,
    // so the task graph, the paths and the AOT wiring are identical either way
    // and a debug `dist/` is as complete as a release one. That matters here
    // because an unembedded build still *runs* against dist/ off disk.
    //
    // The two profiles do share one dist/, so alternating between them re-runs
    // ProGuard and the cache each time. That contention is not new (both
    // profiles always drove the same Gradle build), and Gradle notices, because
    // ProGuardTask declares this an @Input.
    if !should_embed() {
        args.push("-Pkitsune.obfuscate=false");
    }

    let mut cmd = if cfg!(windows) {
        // Rust will not exec a .bat directly, so Windows goes through cmd.
        //
        // `.\gradlew.bat`, not `gradlew.bat`: a bare name makes cmd *search*,
        // and the working directory is only in that search order while
        // NoDefaultCurrentDirectoryInExePath is unset. Some sandboxes and
        // group policies set it, and then the wrapper is simply "not
        // recognized". An explicit relative path is resolved against the
        // working directory instead of searched for, so it is unaffected.
        //
        // Relative and not absolute, though: `cmd /C` mangles a leading quoted
        // token, so an absolute path would break the day this repo lives
        // somewhere with a space in it.
        let mut cmd = Command::new("cmd");
        cmd.arg("/C").arg(r".\gradlew.bat").args(args);
        cmd
    } else {
        // Absolute, because `current_dir` is documented not to decide how a
        // *relative program path* is resolved: that is platform specific and
        // explicitly unstable, and std's own advice is to pass an absolute
        // path. `kotlin` descends from CARGO_MANIFEST_DIR, so it already is
        // one. None of the cmd quoting caveats above apply here: this is an
        // execve, not a shell.
        let mut cmd = Command::new(kotlin.join("gradlew"));
        cmd.args(args);
        cmd
    };

    let path = out.join("gradle.log");
    let log = File::create(&path)
        .unwrap_or_else(|e| panic!("cannot create {}: {e}", path.display()));
    // Two handles onto one file share a file position, so this interleaves the
    // two streams in order rather than having them overwrite each other.
    let errors = log.try_clone().unwrap();

    let status = cmd
        .current_dir(kotlin)
        // Nothing here is interactive, and a Gradle that finds itself with an
        // inherited console can block on a prompt no one will ever see.
        .stdin(Stdio::null())
        .stdout(Stdio::from(log))
        .stderr(Stdio::from(errors))
        .status()
        .unwrap_or_else(|e| panic!("cannot run the Gradle wrapper in {}: {e}", kotlin.display()));

    assert!(
        status.success(),
        "./gradlew shadowJar failed in {} ({status})\n\n{}",
        kotlin.display(),
        fs::read_to_string(&path).unwrap_or_default(),
    );
}

/// Stops this script's stdout and stderr from being inherited by anything the
/// Gradle wrapper spawns.
///
/// Windows hands a child *every* handle marked inheritable, not only the three
/// a parent redirects. Cargo runs a build script with its stdout and stderr on
/// pipes that it reads itself (that is how the `cargo::` directives above get
/// back to it), and those arrive inheritable, so `cmd`, the wrapper JVM and
/// finally the Gradle daemon each end up holding a duplicate of the write ends.
/// The daemon is precisely the one process that outlives the build on purpose,
/// and Cargo reads those pipes until EOF rather than until this script exits.
/// So `cargo build` sits there, silently, long after the build script is gone
/// and with no child process left to point at, until the daemon happens to
/// die: three hours later by default, reset by every build that reuses it.
///
/// Redirecting Gradle's own output to a file (see `run_gradle`) fixes this
/// script's wait but not Cargo's: these duplicates are inherited no matter what
/// the child's own stdio are pointed at. Clearing HANDLE_FLAG_INHERIT closes it
/// at the first hop, and disturbs neither the redirections std sets up for the
/// child (it duplicates those itself, after this runs) nor this script's own
/// writes to stdout.
///
/// Unix needs none of it, hence the no-op: a descriptor survives exec only as
/// 0/1/2 or without CLOEXEC, `run_gradle` redirects the first three, and std
/// opens everything else CLOEXEC.
///
/// What this deliberately does not reach: inheritance is transitive, so if
/// *Cargo's own* stdout is a pipe (`cargo build | tee`, or a CI runner
/// capturing the log), then a duplicate of that pipe was inherited into this
/// process before any of our code ran, at a handle number we have no way to
/// name, and it rides along to the daemon exactly as before. Cargo itself is
/// unaffected and exits; whatever is reading Cargo is the one left waiting. An
/// interactive terminal is a console handle rather than a pipe and does not
/// care, which is why the ordinary case is covered. CI that pipes should be
/// building the two halves separately anyway, which is what
/// `KITSUNE_SKIP_GRADLE=1` is for.
#[cfg(windows)]
fn seal_cargo_pipes() {
    use std::ffi::c_void;
    use std::io::{stderr, stdout};
    use std::os::windows::io::AsRawHandle;

    const HANDLE_FLAG_INHERIT: u32 = 0x0000_0001;

    unsafe extern "system" {
        fn SetHandleInformation(handle: *mut c_void, mask: u32, flags: u32) -> i32;
    }

    // The handles are this process's own and live for its whole lifetime, so
    // there is nothing here to invalidate them. A failure is not worth failing
    // a build over either: the worst case is the hang described above, which is
    // exactly where not calling this at all would leave us.
    unsafe {
        SetHandleInformation(stdout().as_raw_handle(), HANDLE_FLAG_INHERIT, 0);
        SetHandleInformation(stderr().as_raw_handle(), HANDLE_FLAG_INHERIT, 0);
    }
}

#[cfg(not(windows))]
fn seal_cargo_pipes() {}

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
            continue;
        }

        // `--dedup-legal-notices` collapses the per-module legal/ trees by
        // replacing the duplicates with relative symlinks, so dist/ does contain
        // symlinks, six of them, all small licence texts. They are followed and
        // stored as regular files: the archive stays regular-files-only, which
        // costs ~12 KB and keeps unpacking free of link-ordering and
        // escaping-target concerns.
        let target = fs::metadata(&path)
            .unwrap_or_else(|e| panic!("cannot stat {}: {e}", path.display()));
        if !target.is_file() {
            // Neither a regular file nor a link to one. Silently dropping it
            // would produce a runtime image that is subtly wrong rather than
            // obviously broken.
            panic!("unsupported file type in dist: {}", path.display());
        }

        let mut header = tar::Header::new_gnu();
        header.set_size(target.len());
        header.set_mode(mode_of(&target));
        header.set_mtime(0);
        header.set_uid(0);
        header.set_gid(0);
        header.set_entry_type(tar::EntryType::Regular);
        let mut reader = BufReader::new(File::open(&path).unwrap());
        tar.append_data(&mut header, &name, &mut reader).unwrap();
    }
}

/// The only bit that has to survive the round trip is the executable one.
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
