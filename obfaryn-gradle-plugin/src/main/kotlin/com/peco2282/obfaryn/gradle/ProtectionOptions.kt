package com.peco2282.obfaryn.gradle

import com.peco2282.obfaryn.api.protection.BodyFeature
import com.peco2282.obfaryn.api.protection.ControlFlowMode
import com.peco2282.obfaryn.api.protection.ProtectionRule
import com.peco2282.obfaryn.api.protection.TransformationBudget
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import java.util.Locale
import javax.inject.Inject

internal fun flowMode(value: String): ControlFlowMode = ControlFlowMode.values()
  .firstOrNull { it.name.equals(value, ignoreCase = true) }
  ?: throw IllegalArgumentException("Control flow mode must be inherit, none, light or dispatch: $value")

/** Run-wide mode; an explicit mode takes precedence over legacy flags and the protection level. */
open class ControlFlowOptions internal constructor(private val extension: ObfarynExtension) {
  var mode: String
    get() = extension.controlFlowMode
    set(value) { flowMode(value); extension.controlFlowMode = value.lowercase(Locale.ROOT) }
}

/** Prelink generates an API for explicit preparation; it does not execute preparation automatically. */
open class CallOptions internal constructor(private val extension: ObfarynExtension) {
  var enabled: Boolean
    get() = extension.callIndirection
    set(value) { extension.callIndirection = value }
  var prelink: Boolean
    get() = extension.prelinkCalls
    set(value) { extension.prelinkCalls = value }
}

open class ArithmeticOptions internal constructor(private val extension: ObfarynExtension) {
  var enabled: Boolean
    get() = extension.integerArithmetic
    set(value) { extension.integerArithmetic = value }
  var maxOperationsPerMethod: Int
    get() = extension.maxArithmeticOperationsPerMethod
    set(value) { extension.maxArithmeticOperationsPerMethod = value }
}

/** Paths resolve relative to the Gradle project. Null disables an explicitly requested report. */
open class ReportOptions internal constructor(private val extension: ObfarynExtension) {
  var methods: String?
    get() = extension.transformationReport
    set(value) { extension.transformationReport = value }
  var virtualization: String?
    get() = extension.virtualization.report
    set(value) { extension.virtualization.report = value }
}

/** Null feature properties inherit; these permissions never register a disabled pass. */
open class MethodProtectionOptions {
  var strings: Boolean? = null
  var numbers: Boolean? = null
  var calls: Boolean? = null
  var integerArithmetic: Boolean? = null
  var constantFields: Boolean? = null
  var controlFlow = "inherit"
  private var limits: PerformanceOptions? = null

  /** A rule budget replaces the run budget, just like ProtectionRule.budget. */
  fun performance(action: Action<PerformanceOptions>) {
    val options = limits ?: PerformanceOptions().also { limits = it }
    action.execute(options)
  }

  internal fun rule(pattern: String) = ProtectionRule(pattern, listOf(
    BodyFeature.STRINGS to strings, BodyFeature.NUMBERS to numbers, BodyFeature.CALLS to calls,
    BodyFeature.INTEGER_ARITHMETIC to integerArithmetic, BodyFeature.CONSTANT_FIELDS to constantFields,
  ).mapNotNull { (feature, enabled) -> enabled?.let { feature to it } }.toMap(), flowMode(controlFlow), limits?.budget())
}

open class ProtectionRulesOptions @Inject constructor(
  private val extension: ObfarynExtension,
  private val objects: ObjectFactory,
) {
  /** Full-match regex over the original owner.method(descriptor); later rules win per feature. */
  fun method(pattern: String, action: Action<MethodProtectionOptions>) {
    val options = objects.newInstance(MethodProtectionOptions::class.java)
    action.execute(options)
    extension.protectionRules += options.rule(pattern)
  }
}

/** Structural transformation limits; null instruction/branch limits are unlimited. */
open class PerformanceOptions {
  var maxInputInstructions: Int? = null
  var maxInputBranches: Int? = null
  var maxAddedInstructions: Int? = null
  var maxGrowthPercent: Int? = null
  var maxOutputCodeBytes = 65535
  internal fun budget() = TransformationBudget(
    maxInputInstructions, maxInputBranches, maxAddedInstructions, maxGrowthPercent, maxOutputCodeBytes)
}

