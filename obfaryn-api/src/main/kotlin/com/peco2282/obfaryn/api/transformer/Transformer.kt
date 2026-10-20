package com.peco2282.obfaryn.api.transformer

import com.peco2282.obfaryn.api.ObfuscationContext

/**
 * Represents a bytecode transformation step in the obfuscation pipeline.
 *
 * Implementations of this interface inspect, modify, or rewrite class nodes
 * and resources present in the provided [ObfuscationContext].
 * Body-changing passes must consult [ObfuscationContext.transformationPolicy] before editing
 * a method. Use [PipelineConfigurer] to register custom passes without depending on the core engine.
 *
 * A minimal inspection pass can be registered as follows (no core dependency is needed here):
 * ```kotlin
 * val extension = PipelineConfigurer {
 *   add(object : Transformer {
 *     override val id = "example-inspect"
 *     override fun transform(context: ObfuscationContext) {
 *       for (owner in context.classes.values) {
 *         for (method in owner.methods) {
 *           if (!context.transformationPolicy.canTransformBody(owner, method)) continue
 *           // Inspect or safely rewrite this method's instructions here.
 *         }
 *       }
 *     }
 *   })
 * }
 * ```
 * Pass the resulting configurer to the execution host; creating it alone does not execute a pass.
 * Implementations are not automatically discovered through ServiceLoader.
 */
interface Transformer {
  /**
   * The unique identifier for this transformer.
   *
   * Used by [requires], [TransformerRegistry], and [TransformerReport] to identify the pass.
   * Choose a stable ID that does not collide with another registered transformer.
   */
  val id: String

  /**
   * The execution order/priority of this transformer in the pipeline.
   *
   * Transformers with lower order values are executed before transformers
   * with higher values when dependencies allow it. [requires] takes precedence over this
   * numeric preference. The core pipeline breaks equal-order ties by [id], not registration order.
   * Defaults to `0`.
   */
  val order: Int get() = 0

  /**
   * IDs of transformers that must be registered and execute before this pass.
   *
   * The default is no dependencies. Missing dependencies and dependency cycles are rejected
   * before any pass executes; disabling a required pass does not remove this requirement.
   */
  val requires: Set<String> get() = emptySet()

  /**
   * Returns diagnostics for the most recent [transform] invocation.
   *
   * The default report contains this pass's [id] with no metrics or warnings. Stateful passes
   * should reset invocation-specific diagnostics when they run so reports do not accumulate stale data.
   */
  fun report(): TransformerReport = TransformerReport(id)

  /**
   * Executes the transformation on the provided [context].
   *
   * Changes are made in place and are visible to subsequent passes. Register newly generated
   * classes or resources through the context's mutation methods rather than casting its maps.
   * Snapshot a collection before adding/removing its entries during traversal. The API does not
   * recompute frames or repair references for arbitrary edits; the pass and host must coordinate
   * valid output. Throw on an unrecoverable error rather than reporting success with invalid nodes.
   *
   * @param context The obfuscation context containing the target classes and resources.
   */
  fun transform(context: ObfuscationContext)
}

/**
 * Diagnostics produced by a transformer for one invocation.
 *
 * Metric names and warning text are defined by the reporting pass, not by a shared schema.
 * Collections are retained as supplied, so provide snapshots if they may later be mutated.
 *
 * @property id ID of the transformer that produced this report.
 * @property metrics Named counters or measurements; empty when no metrics are reported.
 * @property warnings Human-readable diagnostics that do not themselves signal a failed transformation.
 */
data class TransformerReport(
  val id: String,
  val metrics: Map<String, Long> = emptyMap(),
  val warnings: List<String> = emptyList(),
)
