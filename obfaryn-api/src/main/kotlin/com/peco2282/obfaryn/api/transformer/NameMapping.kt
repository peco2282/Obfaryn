package com.peco2282.obfaryn.api.transformer

/** Member identities always describe the input to the renaming pass. */
data class MemberKey(val owner: String, val name: String, val desc: String)

/** A renamer must rewrite all affected bytecode/resource references itself and publish its changes. */
interface RenamingTransformer : Transformer {
  val classMapping: Map<String, String>
  val methodMapping: Map<MemberKey, String>
  val fieldMapping: Map<MemberKey, String>

  fun mappingText(): String = buildString {
    for ((old, new) in classMapping) append("$old -> $new\n")
    for ((key, new) in methodMapping) append("  ${key.owner}.${key.name}${key.desc} -> $new\n")
    for ((key, new) in fieldMapping) append("  ${key.owner}.${key.name}:${key.desc} -> $new\n")
  }
}
