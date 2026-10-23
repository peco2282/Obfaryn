package com.peco2282.obfaryn.core.protection

import com.peco2282.obfaryn.api.protection.*
import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy as Annotations
import com.peco2282.obfaryn.api.transformer.TransformationPolicy
import org.objectweb.asm.tree.*

/** Resolves original-name rules, class defaults and method overrides beneath legacy restrictions. */
internal class FeaturePolicy(
  private val legacy: TransformationPolicy,
  private val defaultBudget: TransformationBudget,
  rules: List<ProtectionRule>,
  private val identity: (ClassNode, MethodNode) -> String,
) : TransformationPolicy by legacy {
  private val rules = rules.map { Regex(it.methodPattern) to it }

  override fun canTransformBody(owner: ClassNode, method: MethodNode, feature: BodyFeature) =
    exclusionReason(owner, method, feature) == null

  override fun exclusionReason(owner: ClassNode, method: MethodNode, feature: BodyFeature): String? {
    if (!legacy.canTransformBody(owner, method, feature)) return when {
      Annotations.noObfuscation(owner) || Annotations.noObfuscation(method) -> "annotation:NoObfuscation"
      Annotations.has(owner, Annotations.HOT_PATH) || Annotations.has(method, Annotations.HOT_PATH) -> "annotation:HotPath"
      Annotations.renameOnly(owner) || Annotations.renameOnly(method) -> "annotation:RenameOnly"
      else -> "legacy-body-policy"
    }
    var allowed = true
    for ((pattern, rule) in rules) if (pattern.matches(identity(owner, method))) rule.features[feature]?.let { allowed = it }
    val property = when (feature) {
      BodyFeature.STRINGS -> "stringEncryption"
      BodyFeature.NUMBERS -> "numberObfuscation"
      BodyFeature.CALLS -> "callIndirection"
      BodyFeature.INTEGER_ARITHMETIC -> "integerArithmetic"
      BodyFeature.CONSTANT_FIELDS -> "constantFields"
      BodyFeature.CONTROL_FLOW -> "controlFlow"
    }
    for (values in listOf(protection(owner), protection(method))) {
      enumValue(values[property])?.let { value ->
        if (value != "INHERIT") allowed = value != "DISABLED" && value != "NONE"
      }
    }
    if (!allowed || feature == BodyFeature.CONTROL_FLOW && controlFlowMode(owner, method) == ControlFlowMode.NONE) return "feature-policy:$property"
    val budget = budget(owner, method)
    if (budget.maxInputInstructions != null && method.instructions.count { it.opcode >= 0 } > budget.maxInputInstructions!!)
      return "budget:input-instructions"
    if (budget.maxInputBranches != null && branches(method) > budget.maxInputBranches!!) return "budget:input-branches"
    return null
  }

  override fun controlFlowMode(owner: ClassNode, method: MethodNode): ControlFlowMode {
    var result = ControlFlowMode.INHERIT
    for ((pattern, rule) in rules) if (pattern.matches(identity(owner, method)) && rule.controlFlow != ControlFlowMode.INHERIT)
      result = rule.controlFlow
    for (values in listOf(protection(owner), protection(method))) {
      enumValue(values["controlFlow"])?.takeUnless { it == "INHERIT" }?.let { result = ControlFlowMode.valueOf(it) }
    }
    return result
  }

  override fun budget(owner: ClassNode, method: MethodNode): TransformationBudget {
    var result = defaultBudget
    for ((pattern, rule) in rules) if (pattern.matches(identity(owner, method))) rule.budget?.let { result = it }
    for (values in listOf(annotation(owner.visibleAnnotations, owner.invisibleAnnotations, BUDGET),
      annotation(method.visibleAnnotations, method.invisibleAnnotations, BUDGET))) {
      fun limit(name: String, inherited: Int?): Int? {
        val value = values[name] as? Int ?: return inherited
        require(value >= -1) { "Invalid $name on ${identity(owner, method)}: $value" }
        return value.takeUnless { it == -1 } ?: inherited
      }
      result = TransformationBudget(
        limit("maxInputInstructions", result.maxInputInstructions), limit("maxInputBranches", result.maxInputBranches),
        limit("maxAddedInstructions", result.maxAddedInstructions), limit("maxGrowthPercent", result.maxGrowthPercent),
        limit("maxOutputCodeBytes", result.maxOutputCodeBytes)!!,
      )
    }
    return result
  }

  companion object {
    const val PROTECTION = "Lcom/peco2282/obfaryn/annotations/Protection;"
    const val BUDGET = "Lcom/peco2282/obfaryn/annotations/PerformanceBudget;"
    fun branches(method: MethodNode) = method.instructions.count {
      it is JumpInsnNode || it is TableSwitchInsnNode || it is LookupSwitchInsnNode
    }
    private fun enumValue(value: Any?): String? = (value as? Array<*>)?.getOrNull(1) as? String
    private fun protection(owner: ClassNode) = annotation(owner.visibleAnnotations, owner.invisibleAnnotations, PROTECTION)
    private fun protection(method: MethodNode) = annotation(method.visibleAnnotations, method.invisibleAnnotations, PROTECTION)
    private fun annotation(visible: List<AnnotationNode>?, invisible: List<AnnotationNode>?, descriptor: String): Map<String, Any> {
      val values = (visible.orEmpty() + invisible.orEmpty()).firstOrNull { it.desc == descriptor }?.values ?: return emptyMap()
      return values.chunked(2).associate { it[0] as String to it[1] }
    }
  }
}
