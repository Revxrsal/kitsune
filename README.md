<div align="center">

# Kitsune

**Write your Tauri backend in Kotlin.**

Annotate a function, and it shows up in your frontend as a typed
`Promise`-returning binding. No IPC boilerplate, no hand-written command names,
no JSON schemas to keep in sync.

</div>

---

## The pitch

Tauri gives you a tiny, fast shell and a real webview. Rust gives you a fast
host. What neither gives you is the language most application logic is
comfortable in: something with coroutines, null safety, data classes, default
arguments and a huge library ecosystem.

Kitsune bolts a Kotlin runtime onto a Tauri app and makes it feel native to
both sides. You write ordinary Kotlin. A KSP processor reads it at compile time
and writes three things: the dispatch registry on the Kotlin side, the
TypeScript bindings on the frontend side, and the JNI handover on the Rust side.
The three halves cannot drift, because none of them is written by hand.

The JVM it ships is not the one on your machine. It is a `jlink` image trimmed
to the modules your code actually uses, carrying an AOT cache recorded at build
time, embedded straight into the Tauri binary.

## What it looks like

Here is the entire backend of a small app:

```kotlin
package revxrsal.kitsune.test

@ExportFunction
fun version(): String = "1.0"

@ExportFunction
fun add(a: Int, b: Int): Int = a + b

// Default arguments survive the wire. Omitting `times` in TypeScript
// is what makes the Kotlin default apply.
@ExportFunction
fun reverse(input: String = "", times: Int = 1): String =
    input.reversed().repeat(times)

// Nullable *and* defaulted. `{}` and `{ text: null }` are different
// calls, and the generated decoder can tell them apart.
@ExportFunction
fun label(text: String? = "untitled"): String = text ?: "<null>"

// `suspend` is a first-class export, not something you wrap by hand.
@ExportFunction
suspend fun fetch(url: String): String {
    delay(10.milliseconds)
    return "fetched $url"
}

// Members of an `object` work too.
object Store {
    @ExportFunction(name = "load")
    suspend fun load(key: String = "k", limit: Int = 10): String = "loaded $key/$limit"
}
```

And an entrypoint, which is the only wiring you write:

```kotlin
@KitsuneEntrypoint
object TestApplication : KitsuneApplication() {
    init {
        println("Application initialized from an object!")
    }
}
```

Build, and `src/bindings.ts` appears next to your frontend code:

```ts
/** `revxrsal.kitsune.test.add` */
export interface Args_add {
  a: number
  b: number
}

/** `revxrsal.kitsune.test.add` */
export function add(args: Args_add): Promise<number> {
  return Bridge.call(0, args)
}

/** `revxrsal.kitsune.test.Store.load` */
export interface Args_load {
  key?: string
  limit?: number
}

/** `revxrsal.kitsune.test.Store.load` */
export function load(args: Args_load = {}): Promise<string> {
  return Bridge.call(4, args)
}
```

Which you call like any other function:

```tsx
import { add, load, reverse } from './bindings'

const sum = await add({ a: 1, b: 2 })         // 3
const rev = await reverse({ input: 'abc' })   // "cba", `times` defaulted to 1
const row = await load({})                    // both defaults apply
```

Notice what the types encode. `a` and `b` are required because Kotlin declares
them without defaults. `key` and `limit` are optional because Kotlin gives them
defaults, and the whole argument object defaults to `{}` because every parameter
is optional. A `suspend fun` is indistinguishable from a plain one at the call
site: both are a `Promise`. That mapping is derived from the Kotlin
declaration, so it stays true by construction.

## Events, in both directions

Events are declared once and dispatched by ordinal on both sides.

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
listens for is dropped after two array loads, without being decoded at all.

## Why it is fast

Most of the design decisions here are about not paying for things twice.

**CBOR, not JSON.** The payload crosses a JNI boundary as a `byte[]` in either
case. JSON would add a UTF-8 encode on one side and a text parse on the other,
for a format no human is going to read.

**Ordinals, not names.** Every export is numbered at generation time by sorting
its exported name. The wire carries that number, so dispatch is an array index
rather than a string decode plus a hash lookup, and no `String` is allocated
across JNI per call. The three generated halves are regenerated together, which
is the only reason renumbering is safe.

**The ordinal rides on machinery you already paid for.** Inbound, it goes in a
request header, because Tauri's IPC builds a `Headers` map on every call anyway.
Outbound, where events travel down a raw `Channel`, it is a little-endian `u16`
prefixed onto a buffer that had to be allocated to leave the JVM regardless.

**Generated decoders, not reflection.** The processor emits a bespoke
`DeserializationStrategy` per export that reads fields into local slots and
tracks a bitmask of which keys the payload actually carried. That mask is what
makes Kotlin's `$default` synthetic overloads reachable, and what lets an absent
key and an explicit `null` mean different things.

**Nothing blocks the host thread.** A call is submitted to Kotlin, launched in a
scoped coroutine, and completed later through a native callback into a
`oneshot`. Blocking exports are moved to `Dispatchers.IO`; suspending ones run
where they were launched. Dropping the Rust future cancels the Kotlin `Job`.

**A trimmed runtime and a real AOT cache.** `jdeps` computes the module set,
`jlink` builds an image from exactly those, and JEP 483/515 records an AOT cache
against that image. The result, for the sample app, is about 42 MB of runtime,
a 3.9 MB jar and an 11 MB cache.

The cache is not treated as a nice-to-have. The host runs with `-XX:AOTMode=on`,
which turns a rejected cache into a refused VM start rather than a silent
downgrade, and the build fails if the cache does not actually engage. Because
the flags a cache is recorded with must match the flags it is consumed with,
both come from one list in `build.gradle.kts` and are written to
`dist/lib/vmoptions.txt`, which the Rust host reads verbatim.

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
    trainingMainClass.set("revxrsal.kitsune.Training")

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

Linux is what it has been developed against. The runtime path logic covers
macOS and Windows, but neither has been exercised.
