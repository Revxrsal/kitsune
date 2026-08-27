# ProGuard configuration for the Kitsune application jar.
#
# This is name obfuscation, not a security boundary. It renames the compiled
# Kotlin so the shipped jar is more tedious to read; it does not stop a
# determined reader who has the extracted jar in hand.
#
# The one rule everything below serves: anything the Rust host or the JVM
# launcher resolves BY STRING must keep its exact name. Everything reached only
# from Kotlin -> Kotlin is renamed freely. The load-bearing names are:
#
#   1. revxrsal.kitsune.TestApplication  - loaded by name from entrypoint.rs
#   2. NativeFunctionBridge / NativeEventBridge and their static + native methods
#      - resolved by name + descriptor via bind_java_type! in src-tauri (JNI
#        GetStaticMethodID and RegisterNatives)
#   3. revxrsal.kitsune.aot.Training      - launched as `java -cp app.jar <name>`
#        by the AOT cache build, and it scans the jar under its own package
#
# The app dispatches exports by ordinal, not by name, and resolves serializers
# statically, so none of that surface needs keeping.

# --- Conservative baseline --------------------------------------------------
# Rename only. Shrinking and optimization stay off so nothing the ordinal
# dispatch or kotlinx.serialization reaches can be removed or rewritten from
# under it, and so a clean rename can be confirmed before anything more
# aggressive is turned on. Enabling either later is a real possibility, not a
# permanent choice.
-dontshrink
-dontoptimize

# Case-insensitive filesystems (macOS, Windows) cannot tell Foo.class from
# foo.class; without this ProGuard is free to mint both.
-dontusemixedcaseclassnames

# Keep just enough structure for Kotlin generics and the serializers' generic
# signatures to resolve. RuntimeVisibleAnnotations is deliberately NOT kept:
# dropping it strips @kotlin.Metadata, which otherwise carries the original
# names straight through the rename and undoes most of the point. This app has
# no kotlin-reflect on the classpath and never calls the reflective
# serializer() / serializer<T>() paths, so nothing reads annotations at runtime.
# If that changes, add:  -keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions

# Service files (coroutines ships a couple) name implementation classes as text;
# rewrite those names to match the rename map so ServiceLoader still finds them.
-adaptresourcefilecontents META-INF/services/**

# --- 1. The entrypoint the JVM launcher loads by name -----------------------
-keep @revxrsal.kitsune.app.KitsuneEntrypoint class * { *; }
-keep class revxrsal.kitsune.app.KitsuneEntrypoint

# --- 2. The JNI bridge (all resolved by name + descriptor from Rust) --------
-keep class revxrsal.kitsune.functions.NativeFunctionBridge {
    public static void submit(long, int, byte[]);
    public static void cancel(long);
    public static void rustComplete(long, byte[], java.lang.String);
}
-keep class revxrsal.kitsune.event.NativeEventBridge {
    public static void eventReceived(int, byte[]);
    public static boolean kotlinEmittedEvent(int, byte[]);
}

# --- 3. The AOT training main class (build-time launch by name) -------------
# Keeping Training in place also keeps its package non-empty, which its own
# self-scan (check(linked > 0)) depends on.
-keep class revxrsal.kitsune.aot.Training {
    public static void main(java.lang.String[]);
}

# --- 4. kotlinx.serialization (shaded; classic ProGuard won't auto-apply the
# library's bundled consumer rules, so they are inlined here) ----------------
# With shrinking off these mainly guard against renaming that would break a
# reflective serializer() lookup; they are kept anyway so enabling -dontshrink's
# opposite later stays safe. This is the library's canonical rule set.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers public class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# --- 5. Kotlin coroutines (shaded) ------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# --- 6. Compile-only references the stdlib carries but does not ship ---------
-dontwarn org.jetbrains.annotations.**
-dontwarn org.intellij.lang.annotations.**
-dontwarn javax.annotation.**
