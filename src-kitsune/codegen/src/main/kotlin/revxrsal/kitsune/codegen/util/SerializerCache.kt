package revxrsal.kitsune.codegen.util

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.NameAllocator
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.buildCodeBlock

/** `kotlinx.serialization.KSerializer`, the type each cached serializer is held as. */
private val KSerializerClass = com.squareup.kotlinpoet.ClassName("kotlinx.serialization", "KSerializer")

/** Package qualifiers, dropped when a type is turned into an identifier. */
private val QUALIFIER = Regex("""[A-Za-z0-9_]+\.""")

/** Anything left that cannot appear in a Kotlin identifier. */
private val NOT_IDENTIFIER = Regex("""[^A-Za-z0-9_]""")

/**
 * One `KSerializer` per distinct type in the generated file, shared by every
 * wrapper that needs it.
 *
 * `serializer<T>()` is not free at every call site it appears at. The plugin
 * resolves it at compile time, but what it resolves *to* is a constructor call
 * for any type that is not a plain builtin: `serializer<String?>()` becomes
 * `String.serializer().nullable`, which allocates a `NullableSerializer`. Left
 * inline in the decode loop that is one allocation per argument per call — 200
 * bytes each, and the single largest cost in a wrapper that decodes two
 * arguments. `List<String>` and friends are worse.
 *
 * Cached by type rather than by parameter: two functions taking a `String?` want
 * the same serializer, and the descriptor already names the element, so there is
 * nothing per-parameter about it.
 */
class SerializerCache(private val nameAllocator: NameAllocator = NameAllocator()) {

    private val cached = LinkedHashMap<TypeName, PropertySpec>()

    /**
     * The property holding `serializer<[type]>()`, declaring it on first use.
     *
     * Insertion-ordered, so a given set of exports always produces the same file
     * — KSP's incremental machinery compares outputs, and a reshuffled file would
     * invalidate every consumer on every build.
     */
    fun nameFor(type: TypeName): String = cached.getOrPut(type) {
        val identifier = type.toString()
            .replace(QUALIFIER, "")
            .replace("?", "_N")
            .replace(NOT_IDENTIFIER, "_")
        // Backticked: the name carries a `$`, so every reference to it needs
        // them too, exactly as the descriptor and synthetic-binding properties do.
        PropertySpec
            .builder(
                "`" + nameAllocator.newName("serializer$$identifier") + "`",
                KSerializerClass.parameterizedBy(type),
                KModifier.PRIVATE,
            )
            .addKdoc("Shared `%T` serializer; see [%T].\n", type, KSerializerClass)
            .delegate(buildCodeBlock { add("lazy { %M<%T>() }", SerializerFunction, type) })
            .build()
    }.name

    /** Writes every serializer this file ended up needing. */
    fun addTo(file: FileSpec.Builder) {
        cached.values.forEach(file::addProperty)
    }
}
