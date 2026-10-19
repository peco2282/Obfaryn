package com.peco2282.obfaryn.api.transformer

import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode


/**
 * Defines a policy for determining whether specific bytecode elements may be transformed.
 *
 * Obfuscation and optimization passes consult this policy prior to mutating method bodies,
 * ensuring exclusions, annotations, and filtering rules are consistently respected across all transformers.
 * This is a permission check, not a transformation or an automatic guard around ASM mutations.
 * Name preservation and virtualization selection are separate concerns.
 */
fun interface TransformationPolicy {
  /**
   * Determines whether the bytecode body of the given [method] within [owner] can be modified.
   *
   * Call this before editing instructions, exception handlers, or other method-body structures.
   * Returning `true` permits a change but does not guarantee that a particular pass supports the method.
   *
   * @param owner The [ClassNode] declaring or containing the target method.
   * @param method The [MethodNode] candidate for body transformation.
   * @return `true` if the transformer is permitted to rewrite or mutate the method body, `false` otherwise.
   */
  fun canTransformBody(owner: ClassNode, method: MethodNode): Boolean

  /** Permission to strip class and field metadata, independent of method-body decisions. */
  fun canTransformMetadata(owner: ClassNode): Boolean = true

  /** Permission to move a ConstantValue into executable initialization code. */
  fun canTransformConstant(owner: ClassNode, field: FieldNode): Boolean = true

  companion object {
    /**
     * A permissive [TransformationPolicy] instance that permits transformations on all method bodies.
     */
    val ALLOW_ALL = TransformationPolicy { _, _ -> true }
  }
}
