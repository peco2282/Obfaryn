package com.peco2282.obfaryn.annotations

/** Per-feature enable/disable override for [Protection]'s properties. */
enum class ProtectionMode {
  /** Defer to the class annotation, matching rule, or run default. */
  INHERIT,

  /** Force this feature on for the annotated declaration. */
  ENABLED,

  /** Force this feature off for the annotated declaration. */
  DISABLED,
}

/**
 * Explicit control-flow protection override for [Protection.controlFlow].
 *
 * Selection is always annotation- or explicit-pattern-driven; it is never inferred from a
 * method's name and is never expanded through a protection profile on its own.
 */
enum class FlowProtection {
  /** Defer to the class annotation, matching rule, or run default. */
  INHERIT,

  /** Disable control-flow protection for the annotated declaration. */
  NONE,

  /** Lightweight flow obfuscation; cheaper than [DISPATCH] but less thorough. */
  LIGHT,

  /** Interpreter-style dispatch-based control-flow obfuscation. */
  DISPATCH,
}

/**
 * Per-feature permission, inherited from class to method. Global pass enablement still applies.
 * Existing NoObfuscation, RenameOnly and HotPath restrictions cannot be overridden here.
 * Virtualization selection remains exclusively controlled by Virtualize and explicit selectors.
 *
 * @property stringEncryption Override for the string-literal encryption pass.
 * @property numberObfuscation Override for the numeric-literal obfuscation pass.
 * @property controlFlow Override for control-flow protection; see [FlowProtection].
 * @property callIndirection Override for call-indirection protection.
 * @property integerArithmetic Override for the integer-arithmetic obfuscation pass.
 * @property constantFields Override for moving constant fields out of `ConstantValue` attributes.
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

/**
 * -1 inherits the corresponding limit; limits restrict structure, not elapsed execution time.
 *
 * @property maxInputInstructions Skip body passes when the original method has more instructions than this.
 * @property maxInputBranches Skip body passes when the original method has more branch instructions than this.
 * @property maxAddedInstructions Roll a body pass back when it would add more instructions than this.
 * @property maxGrowthPercent Roll a body pass back when output size exceeds this percentage of input size.
 * @property maxOutputCodeBytes Per-method JVM code-length ceiling for this declaration.
 */
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
