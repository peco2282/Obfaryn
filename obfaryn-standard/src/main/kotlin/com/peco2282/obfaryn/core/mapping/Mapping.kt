package com.peco2282.obfaryn.core.mapping

import com.peco2282.obfaryn.api.transformer.MappingFormat
import java.nio.file.Path
import kotlin.io.path.readText

/** A renamed member with its original internal [owner], [name], and JVM [descriptor]. */
data class MappedMember(val owner: String, val name: String, val descriptor: String, val obfuscatedName: String)

/**
 * Parsed class and descriptor-aware member mappings emitted by the built-in renamer.
 * Class keys and values use JVM internal names. Member descriptors and owners describe the
 * original program; only [MappedMember.obfuscatedName] records the replacement member name.
 * Reverse stack-frame lookup uses class/method names only, not source locations or descriptors.
 */
class Mapping private constructor(
  val classes: Map<String, String>,
  val methods: List<MappedMember>,
  val fields: List<MappedMember>,
) {
  private val originalClassByObfuscated = classes.entries.associate { it.value to it.key }
  private val methodByObfuscated = methods.groupBy { (classes[it.owner] ?: it.owner) to it.obfuscatedName }

  /** Looks up an internal class name, returning [obfuscated] unchanged if it is not mapped. */
  fun originalClass(obfuscated: String): String = originalClassByObfuscated[obfuscated] ?: obfuscated

  /**
   * Accepts a dotted or internal class name. A stack frame lacks a descriptor, so lookup succeeds
   * only if all matching entries agree on one original method name; otherwise the input is retained.
   */
  fun originalMethod(obfuscatedClass: String, obfuscatedMethod: String): String {
    val candidates = methodByObfuscated[obfuscatedClass.replace('.', '/') to obfuscatedMethod].orEmpty()
      .map { it.name }.distinct()
    return candidates.singleOrNull() ?: obfuscatedMethod
  }

  /**
   * Rewrites recognized `at owner.method(location)` frames, rendering class names with dots.
   * Locations, line numbers, and surrounding text are preserved; stripped debug information
   * cannot be reconstructed. Frames outside the recognized pattern are left untouched.
   */
  fun deobfuscateStacktrace(text: String): String {
    val frame = Regex("(?m)(\\bat\\s+)([\\w$./]+)\\.([\\w$<>]+)(\\([^\\r\\n]*?\\))")
    return frame.replace(text) { match ->
      val owner = match.groupValues[2]
      val original = originalClass(owner.replace('.', '/')).replace('/', '.')
      val method = originalMethod(owner, match.groupValues[3])
      "${match.groupValues[1]}$original.$method${match.groupValues[4]}"
    }
  }

  companion object {
    /** Reads UTF-8 mapping text from [path] and delegates to [parse]. */
    fun read(path: Path): Mapping = parse(path.readText())

    /**
     * Parses mapping text via [MappingFormat] (the shared format authority) and builds the
     * reverse-lookup view used for de-obfuscation. Package and provenance entries are accepted and
     * ignored here, since stack-frame recovery uses only class and member names.
     *
     * @throws IllegalArgumentException If a line lacks the required mapping structure.
     */
    fun parse(text: String): Mapping {
      val model = MappingFormat.parse(text)
      return Mapping(
        LinkedHashMap(model.classes),
        model.methods.map { (key, obfuscated) -> MappedMember(key.owner, key.name, key.desc, obfuscated) },
        model.fields.map { (key, obfuscated) -> MappedMember(key.owner, key.name, key.desc, obfuscated) },
      )
    }
  }
}
