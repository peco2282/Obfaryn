package com.peco2282.obfaryn.api.transformer

import com.peco2282.obfaryn.api.protection.*
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

  /**
   * Permission to strip metadata belonging to [owner] and its fields.
   *
   * Defaults to `true`, independently of [canTransformBody]. A lambda implementation only
   * customizes body permission; implement an object and override this method to restrict metadata.
   * @return Whether a metadata pass may edit this class's metadata.
   */
  fun canTransformMetadata(owner: ClassNode): Boolean = true

  /**
   * Permission to move [field]'s ConstantValue into executable initialization code in [owner].
   *
   * Defaults to `true`. Also consult [TransformationInputs.keepConstant] and check that the
   * destination initializer may be transformed; permission does not establish bytecode eligibility.
   * @return Whether constant-field conversion is permitted for this declaration.
   */
  fun canTransformConstant(owner: ClassNode, field: FieldNode): Boolean = true

  /** Feature permission supplements, and must not bypass, legacy body policy. */
  fun canTransformBody(owner: ClassNode, method: MethodNode, feature: BodyFeature): Boolean =
    canTransformBody(owner, method)

  fun controlFlowMode(owner: ClassNode, method: MethodNode): ControlFlowMode = ControlFlowMode.INHERIT
  fun budget(owner: ClassNode, method: MethodNode): TransformationBudget = TransformationBudget()
  fun exclusionReason(owner: ClassNode, method: MethodNode, feature: BodyFeature): String? =
    if (canTransformBody(owner, method, feature)) null else "body-policy"

  companion object {
    /**
     * Permits body, metadata, and constant-field transformations via the default method implementations.
     * This does not override target selection, keep rules, or a pass's bytecode safety requirements.
     */
    val ALLOW_ALL = TransformationPolicy { _, _ -> true }
  }
}
