package com.peco2282.obfaryn.annotations

enum class ProtectionMode { INHERIT, ENABLED, DISABLED }
enum class FlowProtection { INHERIT, NONE, LIGHT, DISPATCH }

/**
 * Per-feature permission, inherited from class to method. Global pass enablement still applies.
 * Existing NoObfuscation, RenameOnly and HotPath restrictions cannot be overridden here.
 * Virtualization selection remains exclusively controlled by Virtualize and explicit selectors.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.BINARY)
annotation class Protection(
  val stringEncryption: ProtectionMode = ProtectionMode.INHERIT,
  val numberObfuscation: ProtectionMode = ProtectionMode.INHERIT,
  val controlFlow: FlowProtection = FlowProtection.INHERIT,
  val callIndirection: ProtectionMode = ProtectionMode.INHERIT,
  val integerArithmetic: ProtectionMode = ProtectionMode.INHERIT,
  val constantFields: ProtectionMode = ProtectionMode.INHERIT,
)

/** -1 inherits the corresponding limit; limits restrict structure, not elapsed execution time. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.BINARY)
annotation class PerformanceBudget(
  val maxInputInstructions: Int = -1,
  val maxInputBranches: Int = -1,
  val maxAddedInstructions: Int = -1,
  val maxGrowthPercent: Int = -1,
  val maxOutputCodeBytes: Int = -1,
  /** Minimum added-instruction allowance for the percentage check; -1 inherits, 0 uses a strict ratio. */
  val growthAllowanceInstructions: Int = -1,
)
