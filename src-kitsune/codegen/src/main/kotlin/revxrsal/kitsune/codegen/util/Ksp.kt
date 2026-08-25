package revxrsal.kitsune.codegen.util

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.Modifier

/** Whether this declaration is `suspend`. */
fun KSDeclaration.isSuspend() = Modifier.SUSPEND in modifiers

/** Whether this declaration sits in the package generated code owns. */
fun KSDeclaration.isReservedPackageName() =
    packageName.asString().startsWith(RESERVED_PACKAGE)

/**
 * The enclosing `object` declaration, if this is a member of one.
 *
 * `null` covers both "top-level" and "member of something that isn't an object",
 * which callers distinguish by checking [KSDeclaration.parentDeclaration]
 * separately — a member of a plain class needs an instance the generated code
 * has no way to obtain, and is rejected rather than emitted.
 */
fun KSDeclaration.enclosingObject(): KSClassDeclaration? =
    (parentDeclaration as? KSClassDeclaration)?.takeIf { it.classKind == ClassKind.OBJECT }

/**
 * This class's binary name in JNI internal form — `revxrsal/kitsune/App`, with
 * nested classes joined by `$`.
 *
 * `FindClass` takes this form and no other: the dotted name a Kotlin programmer
 * writes is not a thing the JVM's class loader accepts here, and a nested class
 * written with a dot separator fails to resolve rather than failing to compile.
 *
 * Returns `null` for a local class — one declared inside a function — which has
 * a compiler-assigned binary name that no generated code should depend on.
 */
fun KSClassDeclaration.jniBinaryName(): String? {
    val names = ArrayDeque<String>()
    var current: KSDeclaration = this
    while (true) {
        names.addFirst(current.simpleName.asString())
        val parent = current.parentDeclaration ?: break
        if (parent !is KSClassDeclaration) return null
        current = parent
    }
    val nested = names.joinToString("$")
    val pkg = packageName.asString()
    return if (pkg.isEmpty()) nested else pkg.replace('.', '/') + "/" + nested
}

/**
 * Whether this class has [qualifiedName] somewhere in its supertype chain.
 *
 * Walks rather than asking for a resolved supertype set, because KSP does not
 * offer one, and keeps a visited set: an erroneous hierarchy — a cycle produced
 * by a half-typed edit in an IDE-triggered build — reaches here as a graph, not
 * a tree, and would otherwise recurse until the stack runs out.
 */
fun KSClassDeclaration.isSubclassOf(qualifiedName: String): Boolean =
    isSubclassOf(qualifiedName, HashSet())

private fun KSClassDeclaration.isSubclassOf(
    qualifiedName: String,
    seen: MutableSet<KSClassDeclaration>,
): Boolean {
    if (!seen.add(this)) return false
    for (supertype in superTypes) {
        val declaration = supertype.resolve().declaration as? KSClassDeclaration ?: continue
        if (declaration.qualifiedName?.asString() == qualifiedName) return true
        if (declaration.isSubclassOf(qualifiedName, seen)) return true
    }
    return false
}
