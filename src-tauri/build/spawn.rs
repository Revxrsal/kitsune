//! Launching the Gradle wrapper without handing this process's handles to the
//! Gradle daemon.
//!
//! ## The problem this exists to solve
//!
//! Windows `CreateProcess` with `bInheritHandles = TRUE` gives the child *every*
//! inheritable handle in the parent's table, not only the three a parent
//! redirects, and inheritance is transitive. Cargo runs a build script with its
//! stdio on pipes it reads itself, and when Cargo's *own* stdout is also a pipe
//! (`cargo build | tee`, a CI runner capturing the log, an IDE build panel) a
//! duplicate of that pipe is inherited into this process before any of our code
//! runs, at a handle number nothing here can name.
//!
//! Spawn the wrapper with blanket inheritance and every one of those rides
//! through `cmd` and the wrapper JVM into the Gradle daemon — the one process
//! that outlives the build on purpose. Cargo then exits perfectly happily and
//! whatever is reading Cargo blocks on a write end nobody will ever close, for
//! the daemon's idle timeout: three hours by default, and reset by every build
//! that reuses it. Measured on this repo, the same build took 34.2 s with
//! Cargo's stdout on a file and 664 s on a pipe, and the second only ended
//! because the daemon was killed by hand.
//!
//! ## Why an attribute list
//!
//! `PROC_THREAD_ATTRIBUTE_HANDLE_LIST` is the documented mechanism for exactly
//! this: `bInheritHandles` stays `TRUE`, but the child inherits *only* the
//! handles named in the list. It is what `std` itself intends to use one day
//! (rust-lang/rust#73281, still open), and what `Command::spawn_with_attributes`
//! exposes on nightly (rust-lang/rust#114854) — nightly being why we cannot
//! simply call it.
//!
//! Three things were rejected on the way here:
//!
//! - Clearing `HANDLE_FLAG_INHERIT` across our own table first. Windows has no
//!   documented way to enumerate your own handles, so that means either a loop
//!   up to an invented bound or `NtQuerySystemInformation`, which enumerates
//!   every handle on the machine so you can filter by PID.
//! - `bInheritHandles = FALSE` with `cmd` doing its own `> log 2>&1`. Correct,
//!   but it puts `OUT_DIR` through `cmd`'s parser, and a `&` or `^` in the path
//!   someone cloned into is then a silent misdirect.
//! - `--no-daemon`. Structurally immune and one line, but it is a cold JVM and a
//!   cold configuration on every build that touches Kotlin: 24.0 s against ~1 s
//!   on a warm daemon, measured here.
//!
//! Gradle offers no lever of its own. Its whole documented daemon surface is
//! `--daemon`/`--no-daemon`, `--stop`, `--status` and `org.gradle.java.home`;
//! nothing about stdio or inherited handles. The daemon does close its own
//! streams after handshaking with the client ("Completed writing the daemon
//! greeting. Closing streams...") but it cannot reach a blanket-inherited extra:
//! that handle has no descriptor and no stream object, so nothing in the JVM
//! knows it exists. The same bug is open against Gradle itself
//! (gradle/gradle#3987, filed 2018, still open).
//!
//! ## Unix
//!
//! Needs none of it, hence a plain `Command` there. A descriptor survives exec
//! only as 0/1/2 or without `CLOEXEC`, we redirect the first three, and `std`
//! opens everything else `CLOEXEC`.

use std::fs::File;
use std::io;
use std::path::Path;
use std::process::ExitStatus;

/// Runs the Gradle wrapper in `dir` with `args`, both output streams landing in
/// `log`, and no stdin.
///
/// `log` is taken by value because it has to stay open across the spawn and is
/// of no use to the caller afterwards; read the file back by path instead.
#[cfg(not(windows))]
pub fn gradle(dir: &Path, args: &[&str], log: File) -> io::Result<ExitStatus> {
    use std::process::{Command, Stdio};

    // Two handles onto one file share a file position, so this interleaves the
    // two streams in order rather than having them overwrite each other.
    let errors = log.try_clone()?;

    // Absolute, because `current_dir` is documented not to decide how a
    // *relative program path* is resolved — that is platform specific and
    // explicitly unstable, and std's own advice is to pass an absolute path.
    // `dir` descends from CARGO_MANIFEST_DIR, so it already is one.
    Command::new(dir.join("gradlew"))
        .args(args)
        .current_dir(dir)
        // Nothing here is interactive, and a Gradle that finds itself with an
        // inherited console can block on a prompt no one will ever see.
        .stdin(Stdio::null())
        .stdout(Stdio::from(log))
        .stderr(Stdio::from(errors))
        .status()
}

#[cfg(windows)]
pub fn gradle(dir: &Path, args: &[&str], log: File) -> io::Result<ExitStatus> {
    use std::ffi::c_void;
    use std::os::windows::ffi::OsStrExt;
    use std::os::windows::io::AsRawHandle;
    use std::os::windows::process::ExitStatusExt;
    use std::ptr;

    use windows_sys::Win32::Foundation::{
        CloseHandle, HANDLE, HANDLE_FLAG_INHERIT, SetHandleInformation,
    };
    use windows_sys::Win32::System::Threading::{
        CreateProcessW, DeleteProcThreadAttributeList, EXTENDED_STARTUPINFO_PRESENT,
        InitializeProcThreadAttributeList, PROCESS_INFORMATION, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
        STARTF_USESTDHANDLES, STARTUPINFOEXW, STARTUPINFOW, UpdateProcThreadAttribute,
    };

    // Every argument is a literal in build.rs today and the join below does no
    // quoting at all. Growing a Windows argv quoter here would be reintroducing
    // the exact footgun this module exists to avoid, so refuse the input that
    // would need one instead — loudly, at build time, rather than by handing
    // `cmd` something it will silently mis-split.
    assert!(
        args.iter()
            .all(|a| !a.contains([' ', '\t', '"', '^', '&', '|', '<', '>'])),
        "Gradle argument needs Windows quoting, which this spawner deliberately \
         does not implement: {args:?}"
    );

    // `.\gradlew.bat`, not `gradlew.bat`: a bare name makes cmd *search*, and the
    // working directory is only in that search order while
    // NoDefaultCurrentDirectoryInExePath is unset. Some sandboxes and group
    // policies set it, and then the wrapper is simply "not recognized". An
    // explicit relative path is resolved against the working directory instead
    // of searched for, so it is unaffected.
    //
    // Relative and not absolute because this string is parsed by cmd: an
    // absolute path would need quoting the day this repo lives somewhere with a
    // space in it, and `cmd /C` mangles a leading quoted token. The directory
    // reaches the child as `lpCurrentDirectory` instead, which is a separate
    // parameter no parser ever sees, so a space there is already harmless.
    let line = format!(r"cmd /C .\gradlew.bat {}", args.join(" "));
    let mut line: Vec<u16> = line.encode_utf16().chain(Some(0)).collect();
    let dir: Vec<u16> = dir.as_os_str().encode_wide().chain(Some(0)).collect();

    // Matches `Stdio::null()` on the other branch. Read-only is enough: this is
    // only ever the child's stdin.
    let null = File::open("NUL")?;

    // The list must name every handle the STARTUPINFO below points at, and each
    // one has to be inheritable in our table for the child to receive it —
    // `File` hands back non-inheritable handles, so say so explicitly. Marking
    // them costs nothing elsewhere: this is the only spawn in the build script,
    // and the attribute list is what decides what the child actually gets.
    let handles: [HANDLE; 2] = [null.as_raw_handle() as HANDLE, log.as_raw_handle() as HANDLE];
    for &handle in &handles {
        if unsafe { SetHandleInformation(handle, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT) } == 0 {
            return Err(io::Error::last_os_error());
        }
    }

    // Sized by the usual two-call dance: the first call is *expected* to fail
    // with ERROR_INSUFFICIENT_BUFFER and exists only to fill in `size`.
    let mut size = 0usize;
    unsafe { InitializeProcThreadAttributeList(ptr::null_mut(), 1, 0, &mut size) };

    // Backed by `usize` rather than `u8` so the allocation is pointer-aligned.
    // The attribute list is an opaque struct, not a byte array, and a `Vec<u8>`
    // only promises alignment 1 — in practice the allocator over-delivers, but
    // relying on that is how this file would end up with a bug nobody can
    // reproduce.
    let mut backing = vec![0usize; size.div_ceil(size_of::<usize>()).max(1)];
    let attributes = backing.as_mut_ptr().cast::<c_void>();

    if unsafe { InitializeProcThreadAttributeList(attributes, 1, 0, &mut size) } == 0 {
        return Err(io::Error::last_os_error());
    }

    // The one line that does the work: the child inherits these two handles and
    // nothing else, whatever else happens to be inheritable in our table.
    let updated = unsafe {
        UpdateProcThreadAttribute(
            attributes,
            0,
            PROC_THREAD_ATTRIBUTE_HANDLE_LIST as usize,
            handles.as_ptr().cast::<c_void>(),
            size_of_val(&handles),
            ptr::null_mut(),
            ptr::null(),
        )
    };
    if updated == 0 {
        let err = io::Error::last_os_error();
        unsafe { DeleteProcThreadAttributeList(attributes) };
        return Err(err);
    }

    let mut startup: STARTUPINFOEXW = unsafe { std::mem::zeroed() };
    startup.StartupInfo.cb = size_of::<STARTUPINFOEXW>() as u32;
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput = handles[0];
    // One handle for both, which is what `2>&1` does: the two streams share a
    // file position and interleave in order instead of overwriting each other.
    startup.StartupInfo.hStdOutput = handles[1];
    startup.StartupInfo.hStdError = handles[1];
    startup.lpAttributeList = attributes;

    let mut process: PROCESS_INFORMATION = unsafe { std::mem::zeroed() };
    let spawned = unsafe {
        CreateProcessW(
            ptr::null(),
            line.as_mut_ptr(),
            ptr::null(),
            ptr::null(),
            // Still TRUE: the attribute list narrows what that means, it does
            // not replace it. With FALSE the child would get no stdio at all.
            1,
            EXTENDED_STARTUPINFO_PRESENT,
            ptr::null(),
            dir.as_ptr(),
            ptr::addr_of!(startup.StartupInfo).cast::<STARTUPINFOW>(),
            &mut process,
        )
    };

    // Captured before the cleanup below, which is free to clobber GetLastError.
    let err = io::Error::last_os_error();
    unsafe { DeleteProcThreadAttributeList(attributes) };
    if spawned == 0 {
        return Err(err);
    }

    let status = unsafe { wait(process.hProcess) };
    unsafe {
        CloseHandle(process.hThread);
        CloseHandle(process.hProcess);
    }
    status.map(ExitStatus::from_raw)
}

/// Blocks until `process` exits and reports its exit code.
///
/// # Safety
///
/// `process` must be a live process handle with `SYNCHRONIZE` and
/// `PROCESS_QUERY_LIMITED_INFORMATION` access, as returned by `CreateProcessW`.
#[cfg(windows)]
unsafe fn wait(process: windows_sys::Win32::Foundation::HANDLE) -> io::Result<u32> {
    use windows_sys::Win32::Foundation::WAIT_FAILED;
    use windows_sys::Win32::System::Threading::{GetExitCodeProcess, INFINITE, WaitForSingleObject};

    if unsafe { WaitForSingleObject(process, INFINITE) } == WAIT_FAILED {
        return Err(io::Error::last_os_error());
    }
    let mut code = 0u32;
    if unsafe { GetExitCodeProcess(process, &mut code) } == 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(code)
}
