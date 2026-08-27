<div align="center">

# 🦊 Kitsune

**Write your Tauri backend in Kotlin.**

A highly optimized Kotlin runtime bolted onto Tauri, with all the glue you need
to write your entire backend in Kotlin — invokable directly from your frontend,
fully typed, with zero IPC boilerplate.

</div>

---

Annotate a Kotlin function:

```kotlin
@ExportFunction
fun log(message: String = "ping", level: Int = 1) {
    println("[$level] $message")
}

@ExportFunction
suspend fun fetchFromUrl(url: String): String {
    delay(10.milliseconds)   // real suspend work, on a coroutine
    return "fetched $url"
}
```

Call it from TypeScript as if it were local:

```ts
import { log, fetchFromUrl } from './bindings'

await log({ message: 'hello' })                    // level defaults to 1
const page = await fetchFromUrl({ url: 'https://github.com' })
```

No command names to register, no JSON schemas to keep in sync, no hand-written
IPC. A KSP processor reads your Kotlin at compile time and writes all three
halves of the bridge — the Kotlin dispatch registry, the TypeScript bindings,
and the Rust JNI handover. None of them is written by hand, so none of them can
drift.

## Why Kitsune

Tauri gives you a tiny, fast shell and a real webview. Rust gives you a fast
host. What neither gives you is the language most application logic is
comfortable in — one with coroutines, null safety, data classes, default
arguments and a huge library ecosystem. Kitsune bolts a Kotlin runtime onto a
Tauri app and makes it feel native to both sides.

**First-class Kotlin, not a lowest-common-denominator FFI.** Default arguments,
`suspend` functions, coroutines, nullable types, `object` members — all of them
cross the wire and behave exactly as you'd expect. Exports are driven entirely
by annotations.

**Fast by construction.** Every layer of the bridge was benchmarked and then
rewritten around what the numbers said:

1. **CBOR, not JSON** for JS ⟷ Rust ⟷ Kotlin — the payload crosses as bytes either way, so there's no reason to make a human-readable format nobody reads.
2. **No intermediary representation.** Arguments are encoded once in the frontend and decoded once in Kotlin — no serde value, no JSON string, no map in between.
3. **Zero extra allocations on the call path.** Nothing is copied, stringified or boxed just to move a call across and its result back.
4. **Integer ordinals, not strings.** Function and event calls are addressed by ordinal, so dispatch allocates nothing.
5. **No reflection.** Every serializer, decoder and dispatch table is generated at compile time (like serde), never discovered at runtime.
6. **A trimmed, pre-warmed JVM.** The runtime that ships is a `jlink` image containing only the modules your code uses, carrying an AOT cache recorded at build time and compressed with `zstd` — embedded straight into the Tauri binary.

**Obfuscated on the way out.** The shipped jar is run through ProGuard before it
is embedded, so the compiled Kotlin ships renamed rather than in the clear. The
handful of names the Rust host and the JVM resolve by string — the
`@KitsuneEntrypoint` class, the JNI bridge, the AOT training main — are held
back from the rename; everything else is mangled, and `@kotlin.Metadata` is
stripped so the original names cannot be read straight back out. This is name
obfuscation, not a security boundary — a speed bump for reverse engineering, on
by default and switchable off. See [Configuration](#configuration).

## What it looks like

Here is the entire backend of a small app:

```kotlin
@ExportFunction
fun version(): String {
    return "1.0"
}

@ExportFunction
fun add(a: Int, b: Int): Int {
    return a + b
}

// Default arguments survive the wire. Omitting `times` in TypeScript
// is what makes the Kotlin default apply.
@ExportFunction
fun reverse(input: String = "", times: Int = 1): String {
    return input.reversed().repeat(times)
}

// Nullable *and* defaulted. `{}` and `{ text: null }` are different
// calls, and the generated decoder can tell them apart.
@ExportFunction
fun label(text: String? = "untitled"): String {
    return text ?: "<null>"
}

// `suspend` is a first-class export, not something you wrap by hand.
@ExportFunction
suspend fun fetch(url: String): String {
    delay(10.milliseconds)
    return "fetched $url"
}

// Members of an `object` work too.
object Store {
    @ExportFunction(name = "load")
    suspend fun load(key: String = "k", limit: Int = 10): String {
        return "loaded $key/$limit"
    }
}
```

And an entrypoint, which is the only wiring you write:

```kotlin
@KitsuneEntrypoint
object TestApplication : KitsuneApplication() {
    init {
        println("Application initialized!")
    }
}
```

Build, and `src/bindings.ts` appears next to your frontend code:

```ts
/** `revxrsal.kitsune.aot.version` */
export function version(): Promise<string> { ... }

/** `revxrsal.kitsune.aot.add` */
export function add(args: { a: number; b: number }): Promise<number> { ... }

/** `revxrsal.kitsune.aot.reverse` */
export function reverse(args: { input?: string; times?: number } = {}): Promise<string> { ... }

/** `revxrsal.kitsune.aot.label` */
export function label(args: { text?: string | null } = {}): Promise<string> { ... }

/** `revxrsal.kitsune.aot.fetch` */
export function fetch(args: { url: string }): Promise<string> { ... }

// Members of an `object` are nested under a matching const, so the call site
// mirrors the Kotlin: `Store.load` there, `Store.load` here.
/** Members of the `Store` object. */
export const Store = {
  /** `revxrsal.kitsune.aot.Store.load` */
  load(args: { key?: string; limit?: number } = {}): Promise<string> { ... },
}
```

Which you call like any other function:

```tsx
import { add, reverse, Store } from './bindings'

const sum = await add({ a: 1, b: 2 })         // 3
const rev = await reverse({ input: 'abc' })   // "cba", `times` defaulted to 1
const row = await Store.load()                // both defaults apply
```

Notice what the types encode. `a` and `b` are required because Kotlin declares
them without defaults. `key` and `limit` are optional because Kotlin gives them
defaults, and the whole argument object defaults to `{}` because every parameter
is optional. A member of an `object` lands under a const of the same name, so
`Store.load` in Kotlin is `Store.load` in TypeScript. A `suspend fun` is
indistinguishable from a plain one at the call site: both are a `Promise`. That
mapping is derived from the Kotlin declaration, so it stays true by
construction.

## Events, in both directions

Events are declared once and wired up on both sides for you.

```kotlin
@ExportEvent(id = "clicked")
@Serializable
class ButtonClicked(val x: Int, val y: Int)

@Listener
fun onButtonClicked(event: ButtonClicked) {
    println("clicked at ${event.x}, ${event.y}")
}

@Listener
suspend fun onButtonClickedAsync(event: ButtonClicked) {
    delay(10.milliseconds)
    println("async click handled at ${event.x}, ${event.y}")
}
```

The frontend gets a typed handle:

```ts
import { ButtonClicked } from './bindings'

ButtonClicked.emit({ x: 12, y: 40 })

const stop = ButtonClicked.listen(e => console.log(e.x, e.y))
stop()
```

Suspending listeners each get their own coroutine under a supervisor job, so one
failing or hanging listener cannot take the others down with it. An event nobody
listens for is dropped without being decoded at all.

## How the speed actually works

**CBOR, not JSON.** The payload crosses into the JVM as raw bytes either way. A
text format would mean an encode on one side and a parse on the other, for
something no human is ever going to read.

**No intermediary JSON anywhere.** Your arguments are encoded once in the
frontend and decoded once in Kotlin. There is no serde value, no JSON string and
no map in between, in either direction.

**Zero extra allocations on the call path.** Nothing is copied, stringified or
boxed just to get a call from the webview to Kotlin and its result back. Calls
are addressed by integer ordinal rather than by name, so even dispatch allocates
nothing.

**No reflection.** Every decoder, every dispatch table and every call site is
generated ahead of time at compile time, so nothing has to be discovered at
runtime.

**Coroutines all the way through.** A call never blocks the host thread. Plain
functions run straight through with no coroutine machinery at all; suspending
ones are launched into a supervised scope, delivered back when they complete,
and cancelled on the Kotlin side if the caller goes away.

**A trimmed runtime, warmed ahead of time.** The JVM that ships with your app
contains only the pieces your code actually uses, and it comes with an AOT cache
recorded at build time. Class loading and linking, normally the bulk of what a
JVM does before your first line runs, has already happened by the time the app
launches. For the sample app that is about 42 MB of runtime, a 3.9 MB jar and an
11 MB cache, all embedded in the binary and compressed with `zstd`.

Getting that right takes care, so the build does it for you. The cache is
re-recorded whenever the code it was trained on changes, and every build checks
that it genuinely engages rather than quietly falling back to a cold start. A
build that cannot produce a working cache fails instead of shipping a slow app.

## How it fits together

```
kitsune/
├── src/                     React frontend
│   ├── Bridge.ts            hand-written transport (CBOR, ordinals, event pump)
│   └── bindings.ts          GENERATED, one binding per export
├── src-tauri/               the Rust host
│   ├── src/jvm/             JVM startup, dist layout, embedded image unpacking
│   │   └── entrypoint.rs    GENERATED from @KitsuneEntrypoint
│   ├── src/commands/        call_kt, pending-call table, cancellation
│   ├── src/events/          the event pump
│   ├── src/wire.rs          how each direction carries its ordinal
│   └── build.rs             builds the Kotlin half, embeds dist/ into the binary
└── src-kitsune/             the Kotlin module
    ├── src/main/kotlin/...  your code, plus the small runtime
    ├── codegen/             the KSP processor
    ├── buildSrc/            jlink, jdeps and AOT cache Gradle tasks
    └── dist/                build output: runtime/, lib/app.jar, lib/app.aot
```

The build is a single chain. Running `shadowJar` is finalized by `dist`, which
builds the jlink image, writes the VM options, records the AOT cache and
verifies it engages. `assemble` depends on `dist`, and `check` depends on the
verification, so there is no configuration in which you end up with a jar and a
stale cache. The jar is built directly into `dist/lib` rather than copied there,
so there is only ever one location for it.

On the Rust side, `build.rs` runs Gradle and then embeds `dist/` as a
deterministic zstd tarball, named by its blake3 hash. Release builds unpack it
once into a cache directory keyed by that hash; debug builds skip the embedding
entirely and read `src-kitsune/dist` off disk, so iterating on the Kotlin half
does not mean recompressing 54 MB.

## Getting started

Requirements: JDK 25 (Gradle's toolchain support will fetch one), Rust, and
Node with pnpm.

```bash
pnpm install
cd src-kitsune && ./gradlew dist && cd ..
pnpm tauri dev
```

The Gradle build is what produces `src/bindings.ts` and
`src-tauri/src/jvm/entrypoint.rs`, so run it once before the first `tauri dev`.
After that, `cargo` reruns it for you whenever a tracked Kotlin input changes.

For a release build:

```bash
pnpm tauri build
```

## Writing exports

An `@ExportFunction` must be public, and either top-level or a member of an
`object`. It may be `suspend`. It may return `Unit`, in which case the reply is
zero bytes and the binding is typed `Promise<void>`. Parameters and return types
have to be serializable by kotlinx.serialization, which for your own types means
`@Serializable`.

`@ExportEvent` classes must be `@Serializable`. A `@Listener` takes exactly one
parameter, and may be `suspend`. If you leave `@Listener(event = ...)` blank,
the event is taken from the parameter type.

Names must be unique within the module. Left blank, `@ExportFunction` uses the
function's own name and `@ExportEvent` uses the class's simple name, without the
package, so the Rust and TypeScript sides never have to mirror your Kotlin
package structure.

## Configuration

Everything about how the module becomes an app lives in one block:

```kotlin
kitsune {
    // Where the generated TypeScript lands. Unset means "generate nothing".
    bindings.set(layout.projectDirectory.file("../src/bindings.ts"))

    // Where the generated JNI handover lands.
    entrypoint.set(layout.projectDirectory.file("../src-tauri/src/jvm/entrypoint.rs"))

    // Class whose main() is run to record the AOT profile.
    trainingMainClass.set("revxrsal.kitsune.aot.Training")

    // Shared by the AOT training runs and the host process. Changing this
    // list re-records the cache instead of breaking it.
    vmOptions.set(listOf(
        "-XX:+UseCompactObjectHeaders",
        "-XX:+UseSerialGC",
        "-Xmx512m",
        "--enable-native-access=ALL-UNNAMED",
    ))
}
```

`compression` (`zip-0` through `zip-9`) and `excludeFiles` are available too, for
squeezing the jlink image further.

Obfuscation is on by default and configured here as well:

```kotlin
kitsune {
    // Run the shipped jar through ProGuard. On by default; set false to ship
    // the jar un-renamed.
    obfuscate.set(true)

    // The keep rules. Defaults to proguard-rules.pro beside the build script,
    // which exempts the names the Rust host and the JVM resolve by string.
    obfuscationRules.set(layout.projectDirectory.file("proguard-rules.pro"))

    // The com.guardsquare:proguard-base version. Bump it when the JDK's
    // class-file version outpaces what ProGuard can read.
    proguardVersion.set("7.10.0")
}
```

The jar is run through ProGuard whether or not obfuscation is enabled — with it
off the jar is copied through untouched — so the same `dist/lib/app.jar` layout
is produced either way and the AOT cache always trains against the jar that
actually ships. The default rules keep it to renaming only (`-dontshrink
-dontoptimize`); shrinking and optimization are yours to enable once you have
confirmed a clean build.

## A note on the AOT training run

The cache is recorded by running `Training.main`, not your application's real
entrypoint. Training runs under the bundled JVM with no Rust host in the
process, so anything declared `external` is unbound. Declaring natives is fine,
since the JVM binds one only at its first invocation; *calling* one during
training would throw and fail the build.

`Training` discovers and links every class in the `revxrsal.kitsune` package
tree straight out of the jar, so new code is covered as soon as it compiles. It
loads with `initialize = false` deliberately. As the Kotlin side grows a
connection pool or an HTTP client, constructing those in `Training` is where the
cache starts earning real money.

## Status

This is a working prototype rather than a published library. The sample exports
under `revxrsal.kitsune.test` exist to exercise every shape the codegen has to
handle: no arguments, all-required, defaulted, nullable-and-defaulted, `Unit`,
`object` members, and each of those again as `suspend`. Treat them as the test
suite they are, and delete them when you start your own app.

It has been developed against Linux, macOS and Windows, and the runtime path
logic is verified working on all three.
</parameter>
</invoke>
