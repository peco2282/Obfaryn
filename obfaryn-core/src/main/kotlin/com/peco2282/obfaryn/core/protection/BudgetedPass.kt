package com.peco2282.obfaryn.core.protection

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.protection.*
import com.peco2282.obfaryn.api.transformer.*
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.CodeSizeEvaluator
import org.objectweb.asm.tree.*

internal data class MethodSize(val instructions: Int, val branches: Int, val bytes: Int) {
  companion object {
    fun of(method: MethodNode): MethodSize {
      val evaluator = CodeSizeEvaluator(null)
      method.accept(evaluator)
      return MethodSize(method.instructions.count { it.opcode >= 0 }, FeaturePolicy.branches(method), evaluator.maxSize)
    }
  }
}

/**
 * Class-atomic sandbox: helper fields, initializers, methods and injected classes commit together.
 * A rejected candidate never leaves partial constant extraction or a decoder without its cache.
 */
internal class BudgetedPass(
  private val delegate: Transformer,
  private val feature: BodyFeature,
  private val baseline: Map<String, MethodSize>,
  private val detailed: Boolean,
  private val operationCount: () -> Int?,
) : Transformer by delegate {
  private var result = TransformerReport(delegate.id)
  override fun report() = result

  override fun transform(context: ObfuscationContext) {
    val metrics = linkedMapOf<String, Long>()
    for (key in delegate.report().metrics.keys) metrics[key] = 0L
    if (operationCount() != null) metrics["transformed"] = 0L
    val rows = mutableListOf<MethodTransformationReport>()
    val warnings = mutableListOf<String>()
    var rejectedClasses = 0L
    for (original in context.classes.values.toList()) {
      val candidate = ClassNode().also { original.accept(it) }
      val pending = linkedMapOf<String, ClassNode>()
      val sandbox = object : ObfuscationContext by context {
        override val classes = mapOf(candidate.name to candidate)
        override fun findClass(internalName: String) = when (internalName) {
          candidate.name -> candidate
          else -> pending[internalName] ?: context.findClass(internalName)
        }
        override fun hasClass(internalName: String) = findClass(internalName) != null
        override fun putClass(node: ClassNode) { pending[node.name] = node }
        override operator fun plusAssign(node: ClassNode) { putClass(node) }
        override operator fun plusAssign(entry: com.peco2282.obfaryn.api.context.ResourceEntry) { putResource(entry) }
        override fun removeClass(internalName: String): ClassNode? =
          throw UnsupportedOperationException("Budgeted body passes cannot remove classes")
        override fun putResource(entry: com.peco2282.obfaryn.api.context.ResourceEntry) =
          throw UnsupportedOperationException("Budgeted body passes cannot change resources")
        override fun removeResource(name: String): com.peco2282.obfaryn.api.context.ResourceEntry? =
          throw UnsupportedOperationException("Budgeted body passes cannot change resources")
      }
      val before = original.methods.associateBy { it.name + it.desc }
      delegate.transform(sandbox)
      val classMetrics = delegate.report()
      val count = operationCount()
      val sizes = candidate.methods.associateWith(MethodSize::of)
      var rejection: String? = null
      for ((method, after) in sizes) {
        val old = before[method.name + method.desc]
        val input = old?.let(MethodSize::of) ?: MethodSize(0, 0, 0)
        val identity = context.transformationInputs.methodIdentity(candidate, method)
        val base = baseline[identity] ?: input
        val budget = context.transformationPolicy.budget(candidate, method)
        val reason = when {
          after.bytes > budget.maxOutputCodeBytes -> "budget:output-code-bytes:$identity"
          (old != null || method.access and org.objectweb.asm.Opcodes.ACC_SYNTHETIC == 0) && budget.maxAddedInstructions != null && after.instructions.toLong() - base.instructions > budget.maxAddedInstructions!! ->
            "budget:added-instructions:$identity"
          old != null && budget.maxGrowthPercent != null && after.instructions.toLong() * 100 > maxOf(
            maxOf(1, base.instructions).toLong() * budget.maxGrowthPercent!!,
            (base.instructions.toLong() + budget.growthAllowanceInstructions) * 100,
          ) ->
            "budget:growth-percent:$identity"
          else -> null
        }
        // Unchanged pre-existing methods do not make a class with no candidate edits fail.
        if (reason != null && (old == null || !sameCode(old, method))) { rejection = reason; break }
      }
      val helpers = pending.values.filter { it.name != candidate.name }
      val helperSizes = helpers.associateWith { owner -> owner.methods.associateWith(MethodSize::of) }
      // Injected classes have no original-body growth baseline, but their code-size policy still applies.
      if (rejection == null) for ((owner, methods) in helperSizes) {
        val overLimit = methods.entries.firstOrNull { (method, size) ->
          size.bytes > context.transformationPolicy.budget(owner, method).maxOutputCodeBytes
        }
        if (overLimit != null) {
          rejection = "budget:generated-code-bytes:${context.transformationInputs.methodIdentity(owner, overLimit.key)}"
          break
        }
      }
      if (rejection == null) try {
        for (node in listOf(candidate) + pending.values) ClassWriter(0).also { node.accept(it) }.toByteArray()
      } catch (_: org.objectweb.asm.ClassTooLargeException) {
        rejection = "classfile:constant-pool-limit"
      } catch (_: org.objectweb.asm.MethodTooLargeException) {
        rejection = "classfile:method-code-limit"
      }
      val accepted = rejection == null
      if (accepted) {
        context.putClass(candidate)
        pending.values.forEach(context::putClass)
        for ((key, value) in classMetrics.metrics) if (key != "transformed" || count == null) metrics[key] = (metrics[key] ?: 0) + value
        if (count != null) metrics["transformed"] = (metrics["transformed"] ?: 0) + count
      } else rejectedClasses++
      warnings += classMetrics.warnings
      if (detailed) for (method in candidate.methods) {
        val old = before[method.name + method.desc]
        val input = old?.let(MethodSize::of) ?: MethodSize(0, 0, 0)
        val output = if (accepted) sizes.getValue(method) else input
        val changed = old == null || !sameCode(old, method)
        val denied = context.transformationPolicy.exclusionReason(candidate, method, feature)
        rows += MethodTransformationReport(
          context.transformationInputs.methodIdentity(candidate, method),
          if (changed && !accepted) "rejected" else if (changed) "applied" else "skipped",
          if (changed && !accepted) "class-rollback:$rejection" else if (changed) if (old == null) "generated-helper-or-initializer" else "transformed"
          else denied ?: delegate.skipReason(context, candidate, method) ?: "no-eligible-instructions-or-unsupported",
          input.instructions, output.instructions, input.branches, output.branches, input.bytes, output.bytes,
        )
      }
      if (detailed) for ((owner, methods) in helperSizes) for ((method, size) in methods) {
        val output = if (accepted) size else MethodSize(0, 0, 0)
        rows += MethodTransformationReport(
          context.transformationInputs.methodIdentity(owner, method),
          if (accepted) "applied" else "rejected",
          if (accepted) "generated-helper-class" else "class-rollback:$rejection",
          0, output.instructions, 0, output.branches, 0, output.bytes,
        )
      }
    }
    if (rejectedClasses > 0) warnings += "$id rolled back $rejectedClasses classes due to structural or class-file budgets; transformationReport contains per-method reasons"
    metrics["budgetRejectedClasses"] = rejectedClasses
    result = TransformerReport(id, metrics, warnings.distinct(), rows)
  }

  private fun sameCode(a: MethodNode, b: MethodNode): Boolean {
    fun bytes(method: MethodNode): ByteArray = ClassWriter(0).also { writer ->
      writer.visit(52, 1, "comparison/Owner", null, "java/lang/Object", null)
      method.accept(writer)
      writer.visitEnd()
    }.toByteArray()
    return try {
      bytes(a).contentEquals(bytes(b))
    } catch (_: org.objectweb.asm.MethodTooLargeException) {
      false
    } catch (_: org.objectweb.asm.ClassTooLargeException) {
      false
    }
  }
}
