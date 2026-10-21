package com.peco2282.obfaryn.core.rename

import com.peco2282.obfaryn.core.keep.KeepRules
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode

/** Protects Kotlin compiler conventions that are resolved by name rather than JVM references. */
internal object KotlinMemberConstraints {
  fun protect(nodes: List<ClassNode>, keep: KeepRules) {
    for (node in nodes) {
      val annotation = (node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty())
        .firstOrNull { it.desc == "Lkotlin/Metadata;" }
      if (annotation != null) {
        val entries = annotation.values.orEmpty().chunked(2).associate { it[0] as String to it[1] }
        val multifile = entries["k"] in listOf(4, 5)
        val valueClass = (node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty())
          .any { it.desc == "Lkotlin/jvm/JvmInline;" }
        for (method in node.methods) {
          val annotations = method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty()
          if (multifile || valueClass || node.name.endsWith("\$DefaultImpls") ||
            method.name.endsWith("\$default") || annotations.any { it.desc == "Lkotlin/jvm/JvmStatic;" } ||
            Regex("component[0-9]+").matches(method.name)) {
            keep.keepReflectiveMethod(method.name)
            if (method.name.endsWith("\$default")) keep.keepReflectiveMethod(method.name.removeSuffix("\$default"))
          }
          if (node.access and Opcodes.ACC_ENUM != 0 && method.name in setOf("values", "valueOf", "getEntries")) {
            keep.keepMethod(node.name, method.name, method.desc)
          }
        }
        for (field in node.fields) {
          if (multifile || valueClass || field.name in setOf("INSTANCE", "Companion")) keep.keepField(node.name, field.name)
        }
      }
      // Callable references carry Kotlin names and JVM signatures as strings. Keep their
      // targets until those reference payloads can be rewritten together with metadata.
      if (node.superName?.startsWith("kotlin/jvm/internal/") == true && node.superName.contains("Reference")) {
        val literals = node.methods.flatMap { method -> method.instructions.toArray().filterIsInstance<LdcInsnNode>() }
        val owners = literals.mapNotNull { (it.cst as? Type)?.takeIf { type -> type.sort == Type.OBJECT }?.internalName }
        val strings = literals.mapNotNull { it.cst as? String }
        for (signature in strings.filter { '(' in it }) keep.keepReflectiveMethod(signature.substringBefore('('))
        for (owner in owners) {
          val target = nodes.firstOrNull { it.name == owner } ?: continue
          for (name in strings.filter { '(' !in it }) {
            keep.keepField(owner, name)
            val capitalized = name.replaceFirstChar { it.uppercaseChar() }
            for (method in target.methods.filter { it.name in setOf(name, "get$capitalized", "set$capitalized") }) {
              keep.keepReflectiveMethod(method.name)
            }
          }
        }
      }
    }
  }
}
