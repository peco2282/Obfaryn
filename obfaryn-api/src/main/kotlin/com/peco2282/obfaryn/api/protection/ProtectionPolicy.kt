package com.peco2282.obfaryn.api.protection

/** Independent ordinary body passes. Enabling one never selects interpreter conversion. */
enum class BodyFeature {
  STRINGS, NUMBERS, CONTROL_FLOW, CALLS, INTEGER_ARITHMETIC, CONSTANT_FIELDS
}

enum class ControlFlowMode { INHERIT, NONE, LIGHT, DISPATCH }

/** Structural limits, not a prediction or guarantee of execution time. Null means unlimited. */
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

/** Full-match regex over the ORIGINAL owner.method(descriptor); later rules override earlier ones. */
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
