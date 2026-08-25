package revxrsal.kitsune.codegen.typescript

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier
import revxrsal.kitsune.codegen.util.hasAnnotation
import revxrsal.kitsune.codegen.util.stringArgument

/** The annotations that decide what a class puts on the wire. */
private const val SERIALIZABLE = "kotlinx.serialization.Serializable"
private const val SERIAL_NAME = "kotlinx.serialization.SerialName"
private const val TRANSIENT = "kotlinx.serialization.Transient"

/** What every type the mapper cannot express falls back to. */
private const val UNKNOWN = "unknown"

/** Names that may appear unquoted as a TypeScript property key. */
private val IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")

/**
 * Kotlin types that map onto a TypeScript builtin, keyed by qualified name.
 *
 * Every integer width collapses to `number`, `Long` included. That is not a
 * simplification the mapper is free to make elsewhere — JavaScript numbers carry
 * 53 bits of integer precision, so a `Long` past 2^53 arrives rounded — but it
 * is what `cbor-x` hands the caller, and describing it as anything else would
 * make the binding disagree with the value at runtime.
 *
 * `Char` is a `string` for the same reason: CBOR has no character type, and
 * kotlinx-serialization encodes one as a single-character string.
 */
private val BUILTINS = mapOf(
    "kotlin.String" to "string",
    "kotlin.Char" to "string",
    "kotlin.Boolean" to "boolean",
    "kotlin.Byte" to "number",
    "kotlin.Short" to "number",
    "kotlin.Int" to "number",
    "kotlin.Long" to "number",
    "kotlin.Float" to "number",
    "kotlin.Double" to "number",
    "kotlin.UByte" to "number",
    "kotlin.UShort" to "number",
    "kotlin.UInt" to "number",
    "kotlin.ULong" to "number",
    "kotlin.Unit" to "void",
    "kotlin.Nothing" to "never",
    // `Any` is a payload whose shape the Kotlin side did not commit to, so
    // neither does the binding. `unknown` rather than `any` keeps the caller
    // from acting on it without narrowing first.
    "kotlin.Any" to UNKNOWN,
    // KitsuneCbor sets alwaysUseByteString, so a ByteArray is a CBOR byte string
    // rather than an array of numbers — which is what cbor-x decodes to a
    // Uint8Array. The other primitive arrays are ordinary CBOR arrays.
    "kotlin.ByteArray" to "Uint8Array",
    "kotlin.ShortArray" to "number[]",
    "kotlin.IntArray" to "number[]",
    "kotlin.LongArray" to "number[]",
    "kotlin.FloatArray" to "number[]",
    "kotlin.DoubleArray" to "number[]",
    "kotlin.BooleanArray" to "boolean[]",
    "kotlin.CharArray" to "string[]",
    // Serialized as an ISO-8601 duration string by kotlinx-serialization's
    // builtin serializer, not as a number of anything.
    "kotlin.time.Duration" to "string",
)

/** Kotlin types encoded as a CBOR array of one element type. */
private val SEQUENCES = setOf(
    "kotlin.Array",
    "kotlin.collections.Iterable",
    "kotlin.collections.Collection",
    "kotlin.collections.List",
    "kotlin.collections.MutableList",
    "kotlin.collections.ArrayList",
    "kotlin.collections.Set",
    "kotlin.collections.MutableSet",
    "kotlin.collections.HashSet",
    "kotlin.collections.LinkedHashSet",
)

/** Kotlin types encoded as a CBOR map. */
private val MAPS = setOf(
    "kotlin.collections.Map",
    "kotlin.collections.MutableMap",
    "kotlin.collections.HashMap",
    "kotlin.collections.LinkedHashMap",
)

/**
 * Maps Kotlin types onto TypeScript ones, collecting the declarations the
 * results refer to.
 *
 * What is mapped is the *wire* shape — what `KitsuneCbor` encodes and `cbor-x`
 * hands back — not the Kotlin declaration. The two differ in enough places
 * (`Long`, `Char`, `ByteArray`, value classes) that treating this as a syntactic
 * translation would produce bindings that typecheck and then lie.
 *
 * ## Why not kotlinx-serialization-typescript-generator
 *
 * `adamko-dev/kotlinx-serialization-typescript-generator` solves this exact
 * problem and solves it well, but it walks `SerialDescriptor`s — it is handed a
 * `KSerializer` and reflects over the descriptor tree at runtime. A KSP
 * processor has no serializers to hand it: the classes it is describing are the
 * ones currently being compiled, and their plugin-generated serializers do not
 * exist yet, in this process or any other. Adding it as a dependency would move
 * generation to a separate `JavaExec` task over the built jar, which is a second
 * source of truth for the same set of exports.
 *
 * So the descriptor shape is reproduced from KSP declarations instead, and the
 * type table above is the part worth borrowing from it.
 */
class TsTypes(private val logger: KSPLogger) {

    /**
     * Rendered declarations, keyed by qualified name and in discovery order.
     *
     * A type is inserted before its body is built, so a class that refers back
     * to itself finds its own entry rather than recursing forever. The value is
     * null while that is the case.
     */
    private val declarations = LinkedHashMap<String, String?>()

    /** TypeScript name per qualified Kotlin name, and the names already handed out. */
    private val names = HashMap<String, String>()
    private val taken = HashSet<String>()

    /** Qualified names already reported as unsupported; each is only worth one warning. */
    private val warned = HashSet<String>()

    /**
     * The TypeScript type for [type], declaring anything it refers to.
     *
     * [node] is only used to place diagnostics, and is the declaration the type
     * was read off — a parameter, a property, a return type.
     */
    fun typeOf(type: KSType, node: KSNode?): String {
        val rendered = render(type, node)
        if (!type.isMarkedNullable || rendered == UNKNOWN) return rendered
        // A union has to be parenthesised before `| null` is appended to it;
        // `A | B | null` happens to mean the same thing here, but only because
        // the union is flat, and that is not something to rely on.
        return if (" | " in rendered) "($rendered) | null" else "$rendered | null"
    }

    /** Every declaration reached so far, in the order they were first needed. */
    fun declarations(): List<String> = declarations.values.filterNotNull()

    private fun render(type: KSType, node: KSNode?): String {
        val declaration = type.declaration
        // A typealias is a Kotlin-side name for a shape, and encodes as whatever
        // it expands to. Following it here is what keeps `typealias Headers =
        // Map<String, String>` from arriving as an unknown class.
        if (declaration is KSTypeAlias) return render(declaration.type.resolve(), node)

        val qualifiedName = declaration.qualifiedName?.asString()
            ?: return unsupported("anonymous", "an anonymous type is not addressable", node)

        BUILTINS[qualifiedName]?.let { return it }
        if (qualifiedName in SEQUENCES) return argument(type, 0, node) + "[]"
        if (qualifiedName in MAPS) return map(type, node)
        if (qualifiedName == "kotlin.Pair") {
            return "{ first: ${argument(type, 0, node)}, second: ${argument(type, 1, node)} }"
        }
        if (qualifiedName == "kotlin.Triple") {
            return "{ first: ${argument(type, 0, node)}, second: ${argument(type, 1, node)}, " +
                "third: ${argument(type, 2, node)} }"
        }

        val classDeclaration = declaration as? KSClassDeclaration
            ?: return unsupported(qualifiedName, "$qualifiedName is not a class", node)
        return userType(classDeclaration, qualifiedName, node)
    }

    /**
     * The [index]th type argument of [type].
     *
     * A star projection has no type to resolve — `List<*>` says nothing about
     * its elements — and is the one case where the argument is legitimately
     * missing rather than malformed.
     */
    private fun argument(type: KSType, index: Int, node: KSNode?): String {
        val argument = type.arguments.getOrNull(index)?.type?.resolve()
            ?: return unsupported(
                "${type.declaration.qualifiedName?.asString()}#$index",
                "a star-projected type argument has no shape to describe",
                node,
            )
        return typeOf(argument, node)
    }

    /**
     * A Kotlin map, as the decoder on the other side actually produces it.
     *
     * `cbor-x` turns a CBOR map into a plain object, so a string-keyed map is a
     * `Record`. Anything else keeps the `Map` shape: the keys survive the round
     * trip as their own type, and flattening them into a `Record` would claim
     * property access works when the runtime value has none.
     */
    private fun map(type: KSType, node: KSNode?): String {
        val key = argument(type, 0, node)
        val value = argument(type, 1, node)
        return if (key == "string") "Record<string, $value>" else "Map<$key, $value>"
    }

    private fun userType(
        declaration: KSClassDeclaration,
        qualifiedName: String,
        node: KSNode?,
    ): String {
        // Enums are serializable without the annotation — kotlinx has a builtin
        // serializer for them — so they are answered before it is asked for.
        if (declaration.classKind == ClassKind.ENUM_CLASS) {
            return declare(declaration, qualifiedName) { name -> enum(declaration, name) }
        }

        // A value class is erased by kotlinx-serialization: it encodes as its
        // single underlying value, so the binding must not introduce a wrapper
        // object the payload will never contain.
        if (Modifier.VALUE in declaration.modifiers) {
            val underlying = declaration.primaryConstructor?.parameters?.singleOrNull()
                ?: return unsupported(qualifiedName, "$qualifiedName has no underlying value", node)
            return typeOf(underlying.type.resolve(), node)
        }

        if (!declaration.hasAnnotation(SERIALIZABLE)) {
            return unsupported(
                qualifiedName,
                "$qualifiedName is not @Serializable, so it has no wire shape to describe",
                node,
            )
        }
        if (declaration.typeParameters.isNotEmpty()) {
            return unsupported(qualifiedName, "$qualifiedName is generic", node)
        }
        // Polymorphic payloads carry a discriminator the binding would have to
        // model, and the closed set of subclasses is only knowable for a sealed
        // hierarchy in this same module. Left out until something needs it.
        if (Modifier.SEALED in declaration.modifiers ||
            Modifier.ABSTRACT in declaration.modifiers ||
            declaration.classKind == ClassKind.INTERFACE
        ) {
            return unsupported(
                qualifiedName,
                "$qualifiedName is polymorphic, which the bindings do not model yet",
                node,
            )
        }

        return declare(declaration, qualifiedName) { name ->
            if (declaration.classKind == ClassKind.OBJECT) {
                // An object encodes as an empty structure. `Record<string,
                // never>` is the type of exactly that, where an empty interface
                // would instead be assignable from anything.
                "/** `$qualifiedName` */\nexport type $name = Record<string, never>"
            } else {
                objectInterface(declaration, qualifiedName, name)
            }
        }
    }

    /**
     * Reserves a name for [declaration] and fills in its body, once.
     *
     * The reservation happens before [body] runs, which is what stops a type
     * that contains itself — a tree node, a linked list — from recursing until
     * the stack runs out.
     */
    private fun declare(
        declaration: KSClassDeclaration,
        qualifiedName: String,
        body: (String) -> String,
    ): String {
        names[qualifiedName]?.let { return it }

        val name = allocate(declaration, qualifiedName)
        names[qualifiedName] = name
        declarations[qualifiedName] = null
        declarations[qualifiedName] = body(name)
        return name
    }

    /**
     * The TypeScript name for a Kotlin class.
     *
     * Nested classes are flattened — `Outer.Inner` becomes `Outer_Inner` — since
     * TypeScript has no way to nest an interface inside another. Two classes
     * that still collide after that fall back to the whole qualified name, which
     * is ugly but unambiguous, and warned about so it can be fixed with
     * `@SerialName`-style intent rather than discovered in an editor.
     */
    private fun allocate(declaration: KSClassDeclaration, qualifiedName: String): String {
        val nested = generateSequence(declaration) { it.parentDeclaration as? KSClassDeclaration }
            .map { it.simpleName.asString() }
            .toList()
            .asReversed()
            .joinToString("_")

        if (taken.add(nested)) return nested

        val qualified = qualifiedName.replace('.', '_')
        logger.warn(
            "Kitsune bindings: two exported types are both named '$nested'; $qualifiedName is " +
                "emitted as '$qualified' instead.",
            declaration,
        )
        taken.add(qualified)
        return qualified
    }

    /**
     * The interface for a `@Serializable` class.
     *
     * Optionality mirrors what the *decoder* accepts: a property with a default
     * may be left out of the payload, everything else may not. That is the same
     * rule the generated Kotlin argument descriptors follow, one level down.
     *
     * For an event this means one interface serves both directions, and the `?`
     * is read differently by each — the emitter may omit the key, the listener
     * may receive it absent only if the Kotlin side omitted it too. There is no
     * second shape to split them into as long as both sides send the same class.
     */
    private fun objectInterface(
        declaration: KSClassDeclaration,
        qualifiedName: String,
        name: String,
    ): String {
        val defaults = declaration.primaryConstructor?.parameters.orEmpty()
            .mapNotNull { parameter -> parameter.name?.asString()?.let { it to parameter.hasDefault } }
            .toMap()

        val body = StringBuilder("/** `$qualifiedName` */\nexport interface $name {\n")
        for (property in declaration.getAllProperties()) {
            // No backing field means nothing is encoded: a computed property is
            // derived on the Kotlin side and never appears on the wire.
            if (!property.hasBackingField) continue
            if (property.hasAnnotation(TRANSIENT)) continue

            val declared = property.simpleName.asString()
            val key = property.stringArgument(SERIAL_NAME, "value") ?: declared
            // A property declared in the body rather than the constructor must
            // have an initialiser to exist at all, so it is always optional.
            val optional = defaults[declared] ?: true
            val type = typeOf(property.type.resolve(), property)
            body.append("  ").append(quote(key)).append(if (optional) "?" else "")
                .append(": ").append(type).append("\n")
        }
        return body.append("}").toString()
    }

    /**
     * An enum, as the union of the names it encodes as.
     *
     * A union of string literals rather than a TypeScript `enum`: the payload
     * carries the entry's serial name as a string, and a `const enum`-free union
     * is what a caller can pass a plain string to.
     */
    private fun enum(declaration: KSClassDeclaration, name: String): String {
        val entries = declaration.declarations
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.ENUM_ENTRY }
            .map { it.stringArgument(SERIAL_NAME, "value") ?: it.simpleName.asString() }
            .toList()

        val qualifiedName = declaration.qualifiedName?.asString()
        // An enum with no entries is uninhabited, and `never` says so; the empty
        // union would not even parse.
        val union = if (entries.isEmpty()) "never" else entries.joinToString(" | ") { "'$it'" }
        return "/** `$qualifiedName` */\nexport type $name = $union"
    }

    private fun unsupported(key: String, reason: String, node: KSNode?): String {
        if (warned.add(key)) {
            logger.warn("Kitsune bindings: $reason; it is typed `unknown`.", node)
        }
        return UNKNOWN
    }
}

/** Quotes a property key that cannot be written bare. */
internal fun quote(key: String): String =
    if (IDENTIFIER.matches(key)) key else "'" + key.replace("'", "\\'") + "'"
