package com.peco2282.obfaryn.core.mapping

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
     * Parses `original -> replacement` lines; indentation distinguishes members from classes.
     * Member lines carry their own owner and need not follow a corresponding class line.
     * Blank lines are ignored, repeated class keys use the last value, and member entries are
     * retained in input order. Syntax checks do not validate JVM descriptor correctness.
     *
     * @throws IllegalArgumentException If a line lacks the required mapping structure.
     */
    fun parse(text: String): Mapping {
      val classes = linkedMapOf<String, String>()
      val methods = mutableListOf<MappedMember>()
      val fields = mutableListOf<MappedMember>()
      for (line in text.lineSequence()) {
        if (line.isBlank()) continue
        val parts = line.trim().split(" -> ", limit = 2)
        require(parts.size == 2) { "Invalid mapping line: $line" }
        val (old, new) = parts
        if (!line.first().isWhitespace()) {
          require(old.isNotBlank() && new.isNotBlank()) { "Invalid class mapping: $line" }
          classes[old] = new
        } else {
          val owner = old.substringBeforeLast('.', "")
          val member = old.substringAfterLast('.')
          require(owner.isNotEmpty()) { "Invalid member mapping: $line" }
          if ('(' in member) methods += MappedMember(owner, member.substringBefore('('), member.substring(member.indexOf('(')), new)
          else {
            require(':' in member) { "Invalid field mapping: $line" }
            fields += MappedMember(owner, member.substringBefore(':'), member.substringAfter(':'), new)
          }
        }
      }
      return Mapping(classes, methods, fields)
    }
  }
}
