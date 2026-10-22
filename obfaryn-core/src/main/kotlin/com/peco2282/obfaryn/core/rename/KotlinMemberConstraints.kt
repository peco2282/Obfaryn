package com.peco2282.obfaryn.core.rename

import com.peco2282.obfaryn.core.keep.KeepRules
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

/** Protects Kotlin compiler conventions that are resolved by name rather than JVM references. */
internal object KotlinMemberConstraints {
  /** Value-class ABI hashes depend on Kotlin type names, even when the JVM types are erased. */
  fun needsExplicitJvmName(name: String): Boolean =
    '-' in name && !name.endsWith("-impl") && !name.endsWith("-impl0")

  fun pinJvmNames(original: ClassNode, mapped: ClassNode) {
    if ((original.visibleAnnotations.orEmpty() + original.invisibleAnnotations.orEmpty())
        .none { it.desc == "Lkotlin/Metadata;" }) return
    for ((before, after) in original.methods.zip(mapped.methods)) {
      val existing = (after.visibleAnnotations.orEmpty() + after.invisibleAnnotations.orEmpty())
        .firstOrNull { it.desc == "Lkotlin/jvm/JvmName;" }
      if (existing != null) {
        // Annotation arguments are strings, so ASM's member remapper cannot update them.
        val index = existing.values.indexOf("name")
        if (index >= 0) existing.values[index + 1] = after.name
      } else if (needsExplicitJvmName(before.name) || (before.name != after.name && '-' in before.name)) {
        if (after.invisibleAnnotations == null) after.invisibleAnnotations = mutableListOf()
        after.invisibleAnnotations.add(AnnotationNode("Lkotlin/jvm/JvmName;").apply {
          values = mutableListOf("name", after.name)
        })
      }
    }
  }

  /** Identifies a compiler helper by its flags, Kotlin owner, and actual delegate call. */
  fun defaultTarget(owner: ClassNode, helper: MethodNode): MemberKey? {
    if (!helper.name.endsWith("\$default") ||
      helper.access and (Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC) != (Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC) ||
      (owner.visibleAnnotations.orEmpty() + owner.invisibleAnnotations.orEmpty()).none { it.desc == "Lkotlin/Metadata;" }) return null
    return helper.instructions.toArray().filterIsInstance<MethodInsnNode>()
      .filter { it.name == helper.name.removeSuffix("\$default") }
      .map { MemberKey(it.owner, it.name, it.desc) }.distinct().singleOrNull()
  }

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
          if (multifile || valueClass && method.name in setOf(
              "constructor-impl", "box-impl", "unbox-impl", "equals-impl", "equals-impl0", "hashCode-impl", "toString-impl",
            ) || node.name.endsWith("\$DefaultImpls") ||
            annotations.any { it.desc == "Lkotlin/jvm/JvmStatic;" } ||
            Regex("component[0-9]+").matches(method.name)) {
            keep.keepMethod(node.name, method.name, method.desc)
          }
          if (method.name.endsWith("\$default")) {
            val target = defaultTarget(node, method)
            if (target == null || keep.method(node.name, method.name, method.desc)) {
              keep.keepMethod(node.name, method.name, method.desc)
              method.instructions.toArray().filterIsInstance<MethodInsnNode>()
                .filter { it.name == method.name.removeSuffix("\$default") }
                .forEach { keep.keepMethod(it.owner, it.name, it.desc) }
            }
          }
          if (node.access and Opcodes.ACC_ENUM != 0 && method.name in setOf("values", "valueOf", "getEntries")) {
            keep.keepMethod(node.name, method.name, method.desc)
          }
        }
        for (field in node.fields) {
          if (multifile || field.name in setOf("INSTANCE", "Companion")) keep.keepField(node.name, field.name)
        }
      }
      // Callable references carry Kotlin names and JVM signatures as strings. Keep their
      // targets until those reference payloads can be rewritten together with metadata.
      if (node.superName?.startsWith("kotlin/jvm/internal/") == true && node.superName.contains("Reference")) {
        val literals = node.methods.flatMap { method -> method.instructions.toArray().filterIsInstance<LdcInsnNode>() }
        val owners = literals.mapNotNull { (it.cst as? Type)?.takeIf { type -> type.sort == Type.OBJECT }?.internalName }
        val strings = literals.mapNotNull { it.cst as? String }
        for (owner in owners) for (signature in strings.filter { '(' in it }) {
          keep.keepMethod(owner, signature.substringBefore('('), signature.substring(signature.indexOf('(')))
        }
        for (owner in owners) {
          val target = nodes.firstOrNull { it.name == owner } ?: continue
          for (name in strings.filter { '(' !in it }) {
            keep.keepField(owner, name)
            val capitalized = name.replaceFirstChar { it.uppercaseChar() }
            for (method in target.methods.filter { it.name in setOf(name, "get$capitalized", "set$capitalized") }) {
              keep.keepMethod(owner, method.name, method.desc)
            }
          }
        }
      }
    }
  }
}
