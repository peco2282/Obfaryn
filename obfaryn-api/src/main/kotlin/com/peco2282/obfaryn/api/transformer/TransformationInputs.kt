package com.peco2282.obfaryn.api.transformer

/**
 * Run-specific scope, preservation rules and analysis supplied by the execution host.
 *
 * Consume this through `context.transformationInputs` rather than caching decisions between runs.
 * Names are JVM internal names (`com/example/Main`), not resource paths or descriptors.
 * These analysis decisions do not replace the permission checks in [TransformationPolicy].
 */
interface TransformationInputs {
  /** Stable original identity, including after the built-in renamer. */
  fun methodIdentity(owner: org.objectweb.asm.tree.ClassNode, method: org.objectweb.asm.tree.MethodNode): String =
    owner.name + "." + method.name + method.desc
  /**
   * Whether [internalName] belongs to the application's configured or inferred package scope.
   * Defaults to `false`: missing scope must not select bundled library classes for conversion.
   */
  fun isApplicationClass(internalName: String): Boolean = false
  /**
   * Whether the constant field [field] declared by [owner] must retain its constant representation.
   * Consult this before moving a ConstantValue into executable initialization code, together with
   * [TransformationPolicy.canTransformConstant]. The default supplies no additional keep rule.
   */
  fun keepConstant(owner: String, field: String): Boolean = false
  /**
   * Whether this run requests removal of Kotlin metadata from [internalName].
   * This is a selection decision, not permission to remove other metadata. Consult
   * [TransformationPolicy.canTransformMetadata] as well. Defaults to preserving Kotlin metadata.
   */
  fun shouldStripKotlinMetadata(internalName: String): Boolean = false

  /** Default analysis for hosts that cannot supply run-specific information. */
  companion object {
    /** Unknown application scope must not broaden constant conversion to library classes. */
    val EMPTY: TransformationInputs = object : TransformationInputs {}
  }
}