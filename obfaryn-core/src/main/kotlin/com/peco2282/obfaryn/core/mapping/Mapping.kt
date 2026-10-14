package com.peco2282.obfaryn.core.mapping

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/** The stable, descriptor-aware text mapping written by RenameTransformer. */
data class MappedMember(val owner: String, val name: String, val descriptor: String, val obfuscatedName: String)

class Mapping private constructor(
  val classes: Map<String, String>,
  val methods: List<MappedMember>,
  val fields: List<MappedMember>,
) {
  private val originalClassByObfuscated = classes.entries.associate { it.value to it.key }
  private val methodByObfuscated = methods.groupBy { (classes[it.owner] ?: it.owner) to it.obfuscatedName }

  fun originalClass(obfuscated: String): String = originalClassByObfuscated[obfuscated] ?: obfuscated

  /** A stack frame lacks a descriptor; ambiguous overloads retain their obfuscated method name. */
  fun originalMethod(obfuscatedClass: String, obfuscatedMethod: String): String {
    val candidates = methodByObfuscated[obfuscatedClass.replace('.', '/') to obfuscatedMethod].orEmpty()
      .map { it.name }.distinct()
    return candidates.singleOrNull() ?: obfuscatedMethod
  }

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
    fun read(path: Path): Mapping = parse(path.readText())

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
