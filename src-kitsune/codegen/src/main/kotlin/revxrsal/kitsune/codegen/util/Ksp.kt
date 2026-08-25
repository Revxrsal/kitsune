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
