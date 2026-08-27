package revxrsal.kitsune.codegen.util

/**
 * The number of ordinals the wire format can address.
 *
 * The prefix is a `u16`, so this is where it runs out. 65 536 exports is not a
 * limit anyone reaches by accident, but it is a limit, and silently truncating
 * to sixteen bits would route a call to the wrong function.
 */
const val ORDINAL_LIMIT = 0x1_0000

/**
 * Assigns wire ordinals to a set of exported names.
 *
 * An ordinal is the index into the generated tables, and it is what the payload
 * carries in place of the name, so the Kotlin registry, the TypeScript bindings
 * and anything else generated from these annotations have to agree on it exactly.
 *
 * They cannot agree by construction: the ordinals are handed out by three
 * separate KSP processors, each of which walks the annotated symbols on its own,
 * and nothing orders those walks against each other. So the rule is made
 * independent of the walk instead: **sort the names, number from zero**, which
 * every processor computes to the same answer from the same set, whatever order
 * it saw them in.
 *
 * The consequence is that ordinals are not stable across edits: adding an export
 * renumbers everything after it alphabetically. That is fine here and only here,
 * because all three sides are regenerated from one source tree by one build. It
 * would not be fine if either side were ever published separately.
 */
fun assignOrdinals(names: Iterable<String>): List<String> = names.sorted()
