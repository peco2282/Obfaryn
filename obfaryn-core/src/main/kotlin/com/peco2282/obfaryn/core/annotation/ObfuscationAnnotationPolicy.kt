package com.peco2282.obfaryn.core.annotation

import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode

/**
 * Interprets Obfaryn policy annotations directly from ASM nodes without loading annotation classes.
 * Both runtime-visible and runtime-invisible annotations are recognized by descriptor. Queries
 * only inspect their supplied nodes; [validate] must be called separately to reject conflicts.
 */
object ObfuscationAnnotationPolicy {
  const val VIRTUALIZE = "Lcom/peco2282/obfaryn/annotations/Virtualize;"
  const val HOT_PATH = "Lcom/peco2282/obfaryn/annotations/HotPath;"
  const val RENAME_ONLY = "Lcom/peco2282/obfaryn/annotations/RenameOnly;"
  const val NO_OBFUSCATION = "Lcom/peco2282/obfaryn/annotations/NoObfuscation;"
  const val KEEP_NAME = "Lcom/peco2282/obfaryn/annotations/KeepName;"

  fun has(node: ClassNode, descriptor: String) = has(node.visibleAnnotations, node.invisibleAnnotations, descriptor)
  fun has(node: MethodNode, descriptor: String) = has(node.visibleAnnotations, node.invisibleAnnotations, descriptor)
  fun has(node: FieldNode, descriptor: String) = has(node.visibleAnnotations, node.invisibleAnnotations, descriptor)

  fun noObfuscation(node: ClassNode) = has(node, NO_OBFUSCATION)
  fun noObfuscation(node: MethodNode) = has(node, NO_OBFUSCATION)
  fun noObfuscation(node: FieldNode) = has(node, NO_OBFUSCATION)
  fun renameOnly(node: ClassNode) = has(node, RENAME_ONLY)
  fun renameOnly(node: MethodNode) = has(node, RENAME_ONLY)
  fun keepName(node: ClassNode) = has(node, KEEP_NAME)
  fun keepName(node: MethodNode) = has(node, KEEP_NAME)
  fun keepName(node: FieldNode) = has(node, KEEP_NAME)

  /**
   * Whether annotation policy suppresses ordinary body transformations for [method].
   *
   * Class or method `NoObfuscation`, method `RenameOnly`, and method `HotPath` take precedence.
   * Otherwise a method-level `Virtualize` overrides class-level `RenameOnly` or `HotPath`.
   * `KeepName` does not suppress body changes. This query does not check whether virtualization
   * itself is supported for the method.
   */
  fun skipBody(owner: ClassNode, method: MethodNode): Boolean {
    if (noObfuscation(owner) || noObfuscation(method) || renameOnly(method)) return true
    if (has(method, HOT_PATH)) return true
    if (has(method, VIRTUALIZE)) return false
    return renameOnly(owner) || has(owner, HOT_PATH)
  }

  /**
   * Rejects incompatible annotations on each class, method, or field independently.
   *
   * `NoObfuscation` cannot coexist with `RenameOnly`; `Virtualize` cannot coexist with
   * `HotPath`, `RenameOnly`, or `NoObfuscation` on the same node. Owner/member combinations
   * are not conflicts here and are resolved by queries such as [skipBody].
   *
   * @throws IllegalArgumentException If a conflicting combination is found.
   */
  fun validate(classes: Collection<ClassNode>) {
    for (owner in classes) {
      validate(owner.name, annotations(owner.visibleAnnotations, owner.invisibleAnnotations))
      for (method in owner.methods) validate(
        owner.name + "." + method.name + method.desc,
        annotations(method.visibleAnnotations, method.invisibleAnnotations),
      )
      for (field in owner.fields) validate(
        owner.name + "." + field.name + ":" + field.desc,
        annotations(field.visibleAnnotations, field.invisibleAnnotations),
      )
    }
  }

  private fun validate(target: String, descriptors: Set<String>) {
    require(!(NO_OBFUSCATION in descriptors && RENAME_ONLY in descriptors)) {
      "Conflicting obfuscation annotations on $target: @NoObfuscation and @RenameOnly"
    }
    require(!(VIRTUALIZE in descriptors &&
      descriptors.any { it == HOT_PATH || it == RENAME_ONLY || it == NO_OBFUSCATION })) {
      "Conflicting obfuscation annotations on $target: @Virtualize cannot be combined with @HotPath, @RenameOnly, or @NoObfuscation"
    }
  }

  private fun has(visible: List<AnnotationNode>?, invisible: List<AnnotationNode>?, descriptor: String) =
    annotations(visible, invisible).contains(descriptor)

  private fun annotations(visible: List<AnnotationNode>?, invisible: List<AnnotationNode>?) =
    (visible.orEmpty() + invisible.orEmpty()).mapTo(linkedSetOf()) { it.desc }
}
