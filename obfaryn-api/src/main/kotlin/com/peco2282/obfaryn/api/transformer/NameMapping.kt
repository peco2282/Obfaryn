package com.peco2282.obfaryn.api.transformer

/** Member identities always describe the input to the renaming pass. */
data class MemberKey(val owner: String, val name: String, val desc: String)

/** A renamer must rewrite all affected bytecode/resource references itself and publish its changes. */
interface RenamingTransformer : Transformer {
  val classMapping: Map<String, String>
  val methodMapping: Map<MemberKey, String>
  val fieldMapping: Map<MemberKey, String>

  /**
   * Original-to-new internal package name (`/`-separated, no trailing `/`), for a renamer that
   * relocates whole packages (e.g. package flattening). Empty by default. A `module-info` descriptor's
   * `exports`/`opens` directives are rewritten from this map by host renaming infrastructure; a
   * package absent from this map is reported unchanged, so a renamer should only publish an entry
   * when it moved every class originally in that package — never a partial/best-effort guess.
   */
  val packageMapping: Map<String, String> get() = emptyMap()

  fun mappingText(): String = buildString {
    for ((old, new) in classMapping) append("$old -> $new\n")
    for ((key, new) in methodMapping) append("  ${key.owner}.${key.name}${key.desc} -> $new\n")
    for ((key, new) in fieldMapping) append("  ${key.owner}.${key.name}:${key.desc} -> $new\n")
  }
}
