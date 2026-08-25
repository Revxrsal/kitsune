# `suspend` and default arguments on the JVM

Notes on how the Kotlin compiler lowers `suspend` functions and default
arguments, and how Kitsune's KSP processor generates code against that lowering.

Everything below was verified by compiling probes with Kotlin 2.4.10 and reading
`javap -p` output — it is not inferred from documentation. Signatures are quoted
verbatim.

---

## 1. The two lowerings, separately

### Default arguments → a `$default` synthetic

A function with at least one defaulted parameter gets a second, synthetic method
alongside the real one:

```kotlin
fun syncDefault(a: String = "x", b: Int = 1): Int = a.length + b
```

```
public static final int syncDefault(java.lang.String, int);
public static        int syncDefault$default(java.lang.String, int, int, java.lang.Object);
                                            └─ declared params ─┘  │       │
                                                          mask ────┘       │
                                                          marker ──────────┘
```

- The **mask** is a bitmask: bit *i* set means "argument *i* was **not**
  supplied, substitute the declared default". One `Int` per 32 declared
  parameters, rounded up.
- The **marker** is `kotlin.jvm.internal.DefaultConstructorMarker`, erased to
  `Object` in the descriptor. It exists only to keep the synthetic's signature
  distinct from the real one, and is always `null`.
- A function with **no** defaults gets **no** `$default` method at all.

### `suspend` → an appended `Continuation`, and an `Object` return

```kotlin
suspend fun suspNoDefault(a: String, b: Int): Int = a.length + b
```

```
public static final java.lang.Object suspNoDefault(java.lang.String, int,
                                                   kotlin.coroutines.Continuation<? super java.lang.Integer>);
```

Two things change, and both matter:

1. A `Continuation` is appended **after** the declared parameters.
2. The return type becomes `java.lang.Object`, because the method returns
   *either* the declared value *or* the `COROUTINE_SUSPENDED` sentinel.

---

## 2. What happens when they combine

The two lowerings compose in a fixed order. The `Continuation` is inserted where
the real method has it — after the declared parameters — and the `$default`
machinery is appended after *that*:

```
[receiver] [declared params...] [Continuation] [mask × ⌈n/32⌉] [Object marker]  →  Object
```

The `Continuation` sits **between the parameters and the mask**. This is the one
detail that is easy to get wrong and produces a `NoSuchMethodException` at
runtime rather than a build failure.

### The full observed matrix

| Declaration | `$default` signature | Return |
|---|---|---|
| `suspend fun suspNoParams(): Int` | *none emitted* | — |
| `suspend fun suspNoDefault(a: String, b: Int): Int` | *none emitted* | — |
| `suspend fun suspDefault(a: String = "x", b: Int = 1): Int` | `(String, int, Continuation, int, Object)` | `Object` |
| `suspend fun suspDefaultUnit(a: String = "x")` | `(String, Continuation, int, Object)` | `Object` |
| `suspend fun suspDefaultNullable(a: String? = null, b: Int? = null): String` | `(String, Integer, Continuation, int, Object)` | `Object` |
| `suspend fun suspDefaultPrimitive(d: Double = 1.0, z: Boolean = true, c: Char = 'a', l: Long = 1L): String` | `(double, boolean, char, long, Continuation, int, Object)` | `Object` |
| `suspend fun suspReturnsNullable(a: String = "x"): String?` | `(String, Continuation, int, Object)` | `Object` |
| `SuspObj.suspend fun member(k: String = "k"): String` | `(SuspObj, String, Continuation, int, Object)` | `Object` |
| `suspend fun manyDefaults(p0..p32: Int = 0): Int` | `(33× int, Continuation, int, int, Object)` | `Object` |

Contrast the non-suspend baselines:

| Declaration | `$default` signature | Return |
|---|---|---|
| `fun syncDefault(a: String = "x", b: Int = 1): Int` | `(String, int, int, Object)` | `int` |
| `fun syncDefaultUnit(a: String = "x")` | `(String, int, Object)` | **`void`** |

### Consequences worth stating explicitly

**A `Unit`-returning suspend function returns `Object`, not `void`.** This is the
trap. For a non-suspend function, `Unit` lowers to `void` and the `MethodType`
must name `Void.TYPE`; for a suspend function it lowers to `Object` like every
other suspend function, because it still has to be able to return the sentinel.
Reusing the `void` rule here fails to resolve the method.

**The `Continuation` consumes no mask bit.** Mask bit positions track *declared
Kotlin parameters*. The continuation is not one, so a 33-parameter function
still uses `⌈33/32⌉ = 2` masks and its bits map to `p0..p32` unshifted — even
though the continuation physically sits between `p32` and `mask0`.

**The `Continuation` is raw in the synthetic.** The real method has
`Continuation<? super Integer>`; the `$default` synthetic has a bare
`Continuation`. Generic signatures are erased for method lookup either way, so
`Continuation::class.java` is what matches.

**Nullable primitives box; non-null primitives do not.** `Int? = null` appears as
`java.lang.Integer`, `Int = 1` as `int`. A `MethodType` built with the wrong one
of those does not match.

**`Double` and `Long` occupy two JVM stack slots** but count as one parameter for
both `MethodType` and the mask. No adjustment is needed anywhere.

**A receiver is a real parameter of the synthetic.** For a member of an `object`,
the synthetic is `static` and takes the instance first. It is not counted by the
mask.

---

## 3. How the generated code calls it

For a suspending export with defaults, Kitsune generates three things.

### A functional interface matching the synthetic exactly

```kotlin
private fun interface Default_poll {
  public fun invoke(
    source: String?,
    attempts: Int,
    continuation: Continuation<*>,
    mask0: Int,
    marker: Any?,
  ): Any?
}
```

Notes on why it looks like this:

- **The SAM is not `suspend`.** A `suspend` member would make the compiler append
  a *second* continuation of its own, and the descriptor would stop matching.
  This is a plain method that happens to take a `Continuation`.
- **The return is `Any?`**, mirroring the `Object` return. It is `Any?` even when
  the target declares `Unit`.
- **Reference parameters are widened to nullable.** Same descriptor, but an
  omitted argument is passed as `null`, which a non-null Kotlin parameter would
  reject at compile time.
- **Primitives stay unboxed**, which is what keeps the call allocation-free.
- `Continuation<*>` erases to `Lkotlin/coroutines/Continuation;`, matching the
  raw parameter in the synthetic.

### A `LambdaMetafactory` binding

```kotlin
private val `synthetic$poll`: Default_poll by lazy {
  val lookup = MethodHandles.lookup()
  val signature = MethodType.methodType(
      java.lang.Object::class.java,             // suspend ⇒ Object, never void
      String::class.java,
      Int::class.javaPrimitiveType,
      Continuation::class.java,                 // before the mask
      Int::class.javaPrimitiveType,             // mask0
      java.lang.Object::class.java,             // marker
  )
  val implementation = lookup.findStatic(
      Class.forName("revxrsal.kitsune.app.ApplicationKt"), "poll\$default", signature)
  LambdaMetafactory.metafactory(lookup, "invoke",
      MethodType.methodType(Default_poll::class.java), signature, implementation, signature)
      .target.invokeWithArguments() as Default_poll
}
```

This is the same machinery the JVM uses for `invokedynamic` lambdas, so the
result is an ordinary object with a direct call to the target — not a reflective
dispatch. Bound lazily, so a function never called with omitted arguments never
pays for it.

### The call, via `suspendCoroutineUninterceptedOrReturn`

```kotlin
public suspend fun poll(request: ByteArray): ByteArray {
  val args = KitsuneCbor.decodeFromByteArray(serializer<Args_poll>(), request)
  var mask0 = -1
  if (args.source != null)   { mask0 = mask0 and 0xfffffffe.toInt() }
  if (args.attempts != null) { mask0 = mask0 and 0xfffffffd.toInt() }

  if (mask0 == 0xfffffffc.toInt()) {
    // Everything was supplied — call the real function directly.
    val result = revxrsal.kitsune.app.poll(source = args.source!!, attempts = args.attempts!!)
    return KitsuneCbor.encodeToByteArray(serializer<String>(), result)
  }

  val result = suspendCoroutineUninterceptedOrReturn<String> { continuation ->
    `synthetic$poll`.invoke(args.source, args.attempts ?: 0, continuation, mask0,
                            DEFAULT_CONSTRUCTOR_MARKER)
  }
  return KitsuneCbor.encodeToByteArray(serializer<String>(), result)
}
```

`suspendCoroutineUninterceptedOrReturn` is the key, and the fit is exact rather
than convenient:

> Its block must return **either** a value **or** `COROUTINE_SUSPENDED`, having
> arranged for the continuation to be resumed later.

That is *precisely* the contract of the `$default` synthetic. So the caller's own
continuation goes straight into the `Continuation` slot and the sentinel is
propagated untouched. There is no wrapper continuation, no extra stack frame, and
no `suspendCoroutine`-style callback bridge — it is the same handoff the compiler
emits for an ordinary suspend call.

**Why "unintercepted" is correct, not a shortcut.** Interception (dispatching onto
a `CoroutineDispatcher`) happens *once*, where the coroutine is started, not at
each suspension point. The compiler passes the raw continuation at every ordinary
suspend call site too. Using the intercepted variant here would add a dispatch
per call that a direct Kotlin call would not have.

### Absent primitives

For an omitted primitive the generated code passes the type's zero value
(`args.attempts ?: 0`). The value is never observed — the mask tells the callee
to overwrite it — but the interface parameter is an unboxed `Int`, so there is no
`null` to pass in the first place.

---

## 4. Presence tracking, and its one ambiguity

The mask is computed from the decoded argument holder, not from a side channel.
Every defaulted parameter is widened to nullable on the holder — **including
primitives**, which is what makes absence representable:

```kotlin
@Serializable
public class Args_poll(
  public val source: String? = null,
  public val attempts: Int? = null,
)
```

`null` ⇒ not supplied ⇒ leave the mask bit set ⇒ the compiler substitutes the
declared default.

This is unambiguous for a non-null declared parameter. It is **ambiguous for a
parameter that is both nullable and defaulted** (`x: Int? = 5`): an explicit
`null` from the host and an omitted argument decode identically, and the declared
default wins. The processor emits a warning for that combination. `x: Int? = null`
is fine, because both readings agree.

---

## 5. Where the shapes are decided in the processor

| Concern | Location |
|---|---|
| `isSuspend` on the model | `functions/ExportedFun.kt` |
| SAM shape (`Continuation`, `Any?` return) | `ExportedFun.createSyntheticInterface` |
| `MethodType` (`Object` return, `Continuation` position) | `ExportedFun.createSyntheticProperty` |
| `suspendCoroutineUninterceptedOrReturn` emission | `ExportedFun.callThroughSynthetic` |
| Mask bit walk (declared parameters only) | `ExportedFun.computeMask` |
| Holder nullability widening | `functions/ExportedParameter.kt` |
| `Blocking` vs `Suspending` registry entry | `functions/FunctionsFile.kt` |
| `listener` vs `suspendingListener` | `event/EventsFile.kt` |

---

## 6. Runtime dispatch

A suspending export cannot be called the way a plain one is, so the registry
keeps them apart rather than erasing the difference:

```kotlin
sealed interface ExportedFunction {
    fun interface Blocking   : ExportedFunction { operator fun invoke(request: ByteArray): ByteArray }
    fun interface Suspending : ExportedFunction { suspend operator fun invoke(request: ByteArray): ByteArray }
}
```

`FunctionHandler` offers three entry points:

- `call(name, request)` — plain exports only; **throws** for a suspending one
  rather than silently blocking the host's JNI thread.
- `callBlocking(name, request)` — either kind, blocking the caller. The adapter
  for today's synchronous single-method bridge.
- `launchCall(name, request, onComplete)` — either kind, launched into
  `KitsuneScope`; the outcome arrives as a `Result` on whichever thread the
  coroutine finished on.

Suspending `@Listener` functions are registered through `suspendingListener` and
launched — each in its own coroutine, so one cannot delay or (under the scope's
supervisor job) cancel another. `dispatch` therefore cannot report their failures;
the scope's `CoroutineExceptionHandler` does.

### The scope: not `GlobalScope`

```kotlin
object KitsuneScope : CoroutineScope {
    override val coroutineContext =
        SupervisorJob() + Dispatchers.Default + CoroutineName("kitsune") + exceptionHandler
    fun shutdown() = cancel()
}
```

- `GlobalScope` has no lifecycle, so the host has no way to stop in-flight work.
- Its children are not supervised: two exports from unrelated host calls would be
  siblings under one job, and a failure in either cancels the other.
  `SupervisorJob` makes each direct child fail alone.
- An exception escaping a `launch` has nowhere to go but the thread's default
  handler, which across a JNI boundary is a silent loss.
- It is `@DelicateCoroutinesApi` for exactly these reasons.

`Dispatchers.Default` is the base because it is sized for CPU work and is the
neutral choice for code the bridge does not control. An export that blocks —
JDBC, a synchronous HTTP client — is responsible for its own
`withContext(Dispatchers.IO)`, as it would be anywhere else.

---

## 7. Verified at runtime

Not just compiled — each of these was executed and its result checked, with a
real `delay()` inside the target so the synthetic genuinely returns
`COROUTINE_SUSPENDED` and resumes rather than completing inline:

| Case | Result |
|---|---|
| suspend, no defaults | `fetch(x)` → `fetched x` |
| suspend + defaults, all supplied (direct path) | `poll(a,9)` → `polled a x9` |
| suspend + defaults, one omitted (synthetic) | `poll(a,_)` → `polled a x3` |
| suspend + defaults, all omitted (synthetic) | `poll(_,_)` → `polled default x3` |
| suspend + `Unit` + defaults omitted | `warm(_)` → 0 bytes, side effect ran |
| suspend member of `object`, omitted | `load(_,_)` → `loaded k/10` |
| suspend member of `object`, supplied | `load(k,5)` → `loaded k/5` |
| 33 defaulted params, low mask (synthetic) | `many(p0=5)` → `p31=100 p32=200 p0=5` |
| 33 defaulted params, high mask (synthetic) | `many(p32=9)` → `p31=100 p32=9 p0=0` |
| `call()` on a suspending export | throws, naming `callBlocking`/`launchCall` |
| `launchCall` on suspending / on blocking | `Success(polled default x7)` / `Success(3)` |
| blocking listener + suspending listener on one event | both ran, blocking first |
