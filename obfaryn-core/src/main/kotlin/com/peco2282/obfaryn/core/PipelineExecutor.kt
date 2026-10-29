package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.transformer.*
import com.peco2282.obfaryn.api.protection.MethodTransformationReport
import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.protection.*
import com.peco2282.obfaryn.core.transformer.FilteredContext
import org.objectweb.asm.commons.Remapper

/** Executes any registered parts with the same budgeting, identity tracking and reporting. */
class PipelineExecutor {
  fun execute(
    context: ObfuscationContext,
    passes: List<Transformer>,
    excludedClasses: Set<String> = emptySet(),
    excludedResources: Set<String> = emptySet(),
    detailed: Boolean = false,
  ): List<TransformerReport> {
    require(passes.count { it is RenamingTransformer } <= 1) { "Only one renaming pass may run per pipeline" }
    val baseline = context.classes.values.flatMap { owner -> owner.methods.map { method ->
      context.transformationInputs.methodIdentity(owner, method) to MethodSize.of(method)
    } }.toMap()
    return passes.map { transformer ->
      try {
        val renamer = transformer as? RenamingTransformer
        val originalClasses = if (renamer != null) context.classes.keys.toList() else emptyList()
        val identities = if (renamer != null) context.classes.values.flatMap { owner -> owner.methods.map { method ->
          MemberKey(owner.name, method.name, method.desc) to context.transformationInputs.methodIdentity(owner, method)
        } }.toMap() else emptyMap()
        val feature = transformer.isolatedBodyFeature
        val effective = if (feature != null) BudgetedPass(transformer, feature, baseline, detailed,
          { transformer.transformedCount }) else transformer
        effective.transform(if (renamer != null) context else FilteredContext(context, excludedClasses, excludedResources))
        if (renamer != null) {
          val descriptorMapper = object : Remapper() {
            override fun map(name: String) = renamer.classMapping[name] ?: name
          }
          val oldClasses = context.originalClassNames.toMap()
          for (owner in originalClasses) {
            val mapped = renamer.classMapping[owner] ?: owner
            context.originalClassNames[mapped] = oldClasses[owner] ?: owner
          }
          for ((key, original) in identities) {
            val owner = renamer.classMapping[key.owner] ?: key.owner
            val name = renamer.methodMapping[key] ?: key.name
            context.originalMethodIdentities[owner + "." + name + descriptorMapper.mapMethodDesc(key.desc)] = original
          }
        }
        var report = effective.report()
        if (effective === transformer) transformer.transformedCount?.let {
          report = report.copy(metrics = report.metrics + ("transformed" to it.toLong()))
        }
        if (detailed && feature != null) {
          val excludedRows = excludedClasses.flatMap { name ->
            val owner = context.findClass(name) ?: return@flatMap emptyList()
            owner.methods.map { method ->
              val size = MethodSize.of(method)
              MethodTransformationReport(context.transformationInputs.methodIdentity(owner, method), "skipped",
                if (com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy.noObfuscation(owner)) "annotation:NoObfuscation"
                else "class-exclusion-or-application-scope", size.instructions, size.instructions,
                size.branches, size.branches, size.bytes, size.bytes)
            }
          }
          report = report.copy(methods = report.methods + excludedRows)
        }
        report
      } catch (cause: Exception) {
        throw IllegalStateException("Failed transforming with ${transformer.id}", cause)
      }
    }
  }
}
