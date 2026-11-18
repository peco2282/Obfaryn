package com.peco2282.obfaryn.api.transformer

/**
 * Member identities always describe the input to the renaming pass.
 *
 * @property owner Original internal class name declaring the member.
 * @property name Original member name, before renaming.
 * @property desc JVM descriptor of the member (method descriptor, or field type descriptor).
 */
data class MemberKey(val owner: String, val name: String, val desc: String) {
  companion object {
    /**
     * Builds a key with [owner] normalized to JVM internal form (dots to slashes), so a caller
     * holding a class name in dotted form (`com.example.Foo`) still produces a key equal to one
     * built from bytecode-level internal names (`com/example/Foo`). [name] and [desc] are taken
     * verbatim; [desc] must already be a JVM descriptor. Prefer this over the raw constructor
     * whenever [owner] comes from an external or hand-written source rather than straight from a
     * `ClassNode`/instruction, which are already internal.
     */
    @JvmStatic
    fun of(owner: String, name: String, desc: String): MemberKey = MemberKey(owner.replace('.', '/'), name, desc)
  }
}

/** A renamer must rewrite all affected bytecode/resource references itself and publish its changes. */
interface RenamingTransformer : Transformer {
  /** Original-to-new internal class name, for every class this renamer renamed. */
  val classMapping: Map<String, String>

  /** Original-to-new method name, keyed by the method's original identity. */
  val methodMapping: Map<MemberKey, String>

  /** Original-to-new field name, keyed by the field's original identity. */
  val fieldMapping: Map<MemberKey, String>

  /**
   * Original-to-new internal package name (`/`-separated, no trailing `/`), for a renamer that
   * relocates whole packages (e.g. package flattening). Empty by default. A `module-info` descriptor's
   * `exports`/`opens` directives are rewritten from this map by host renaming infrastructure; a
   * package absent from this map is reported unchanged, so a renamer should only publish an entry
   * when it moved every class originally in that package — never a partial/best-effort guess.
   */
  val packageMapping: Map<String, String> get() = emptyMap()

  /** Renders [classMapping]/[methodMapping]/[fieldMapping] as a human-readable mapping report. */
  fun mappingText(): String = buildString {
    for ((old, new) in classMapping) append("$old -> $new\n")
    for ((key, new) in methodMapping) append("  ${key.owner}.${key.name}${key.desc} -> $new\n")
    for ((key, new) in fieldMapping) append("  ${key.owner}.${key.name}:${key.desc} -> $new\n")
  }
}
