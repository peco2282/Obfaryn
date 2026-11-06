package com.peco2282.obfaryn.api.protection

/** Independent ordinary body passes. Enabling one never selects interpreter conversion. */
data class BodyFeature(val name: String) {
  init { require(Regex("[A-Za-z][A-Za-z0-9_.:-]*").matches(name)) { "Invalid body feature: $name" } }
  override fun toString() = name
  companion object {
    @JvmField val STRINGS = BodyFeature("STRINGS")
    @JvmField val NUMBERS = BodyFeature("NUMBERS")
    @JvmField val CONTROL_FLOW = BodyFeature("CONTROL_FLOW")
    @JvmField val CALLS = BodyFeature("CALLS")
    @JvmField val INTEGER_ARITHMETIC = BodyFeature("INTEGER_ARITHMETIC")
    @JvmField val CONSTANT_FIELDS = BodyFeature("CONSTANT_FIELDS")
    /** Built-in names only; custom IDs do not require global registration. */
    @JvmStatic fun values() = arrayOf(STRINGS, NUMBERS, CONTROL_FLOW, CALLS, INTEGER_ARITHMETIC, CONSTANT_FIELDS)
  }
}

/**
 * Explicit control-flow protection selection for one method.
 *
 * Selection is always annotation- or explicit-pattern-driven; it is never inferred from a method's
 * name (such as `tick`/`onTick`) and is never expanded through a protection profile on its own.
 */
enum class ControlFlowMode {
  /** Defer to whatever the enclosing scope (class annotation, matching rule, or run default) selects. */
  INHERIT,

  /** Disable registration of the control-flow pass for this method, overriding any broader default. */
  NONE,

  /** Lightweight flow obfuscation; cheaper than [DISPATCH] but less thorough. */
  LIGHT,

  /** Interpreter-style dispatch-based control-flow obfuscation. */
  DISPATCH,
}

/**
 * Structural limits on a body pass, not a prediction or guarantee of execution time. Null means
 * unlimited for that limit.
 *
 * @property maxInputInstructions Skip the pass when the original method has more instructions than this.
 * @property maxInputBranches Skip the pass when the original method has more branch instructions than this.
 * @property maxAddedInstructions Roll the pass back when it would add more instructions than this.
 * @property maxGrowthPercent Roll the pass back when output size exceeds this percentage of input size
 *   (100 means no growth allowed beyond [growthAllowanceInstructions]); must be at least 100 when set.
 * @property maxOutputCodeBytes Per-method JVM code-length ceiling; must stay within the class-file limit
 *   of 65535 bytes.
 */
data class TransformationBudget @JvmOverloads constructor(
  val maxInputInstructions: Int? = null,
  val maxInputBranches: Int? = null,
  val maxAddedInstructions: Int? = null,
  val maxGrowthPercent: Int? = null,
  val maxOutputCodeBytes: Int = 65535,
  /** Minimum cumulative added-instruction allowance for the percentage check; 0 restores a strict ratio. */
  val growthAllowanceInstructions: Int = 64,
) {
  init {
    listOfNotNull(maxInputInstructions, maxInputBranches, maxAddedInstructions).forEach {
      require(it >= 0) { "Instruction and branch budgets must be non-negative" }
    }
    require(growthAllowanceInstructions >= 0) { "Growth allowance must be non-negative" }
    require(maxGrowthPercent == null || maxGrowthPercent >= 100) { "Growth percent must be at least 100" }
    require(maxOutputCodeBytes in 1..65535) { "Output code budget must be in 1..65535" }
  }

  companion object {
    /** Conservative starting point; benchmark the actual workload. */
    @JvmStatic fun latencySensitive() = TransformationBudget(2000, 128, 256, 175, 60000)
  }
}

/**
 * One matching rule in a run's protection profile.
 *
 * [methodPattern] is a full-match regex over the ORIGINAL `owner.method(descriptor)` identity
 * (never the renamed/obfuscated one). When several rules match the same method, later rules in
 * the configured list override earlier ones on a per-property basis: a later rule that leaves
 * [controlFlow] at [ControlFlowMode.INHERIT] does not clear an earlier rule's explicit choice.
 *
 * @property methodPattern Regex matched against the original method identity.
 * @property features Per-[BodyFeature] enable/disable overrides for methods this rule matches.
 * @property controlFlow Explicit control-flow selection for methods this rule matches; see [ControlFlowMode].
 * @property budget Transformation budget override for methods this rule matches, or null to inherit.
 */
data class ProtectionRule @JvmOverloads constructor(
  val methodPattern: String,
  val features: Map<BodyFeature, Boolean> = emptyMap(),
  val controlFlow: ControlFlowMode = ControlFlowMode.INHERIT,
  val budget: TransformationBudget? = null,
) {
  init { Regex(methodPattern) }
}

/**
 * A pass decision for one method. Counts exclude labels, line numbers and stack-map frames.
 * Code-byte counts are conservative ASM upper estimates, not measured native/JIT code sizes.
 *
 * @property method The original (pre-transformation) method identity this report describes.
 * @property status Outcome of the pass, such as whether it committed or was rejected/rolled back.
 * @property reason Human-readable explanation for [status], such as which budget was exceeded.
 * @property inputInstructions Instruction count of the original method body.
 * @property outputInstructions Instruction count after the pass, or equal to [inputInstructions] when rolled back.
 * @property inputBranches Branch instruction count of the original method body.
 * @property outputBranches Branch instruction count after the pass.
 * @property inputCodeBytes Estimated JVM code length of the original method body.
 * @property outputCodeBytes Estimated JVM code length after the pass; 0 for a rejected new method.
 */
data class MethodTransformationReport(
  val method: String,
  val status: String,
  val reason: String,
  val inputInstructions: Int,
  val outputInstructions: Int,
  val inputBranches: Int,
  val outputBranches: Int,
  val inputCodeBytes: Int,
  val outputCodeBytes: Int,
)
