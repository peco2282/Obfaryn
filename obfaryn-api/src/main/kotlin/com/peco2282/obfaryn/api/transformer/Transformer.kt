package com.peco2282.obfaryn.api.transformer

import com.peco2282.obfaryn.api.ObfuscationContext

/**
 * Represents a bytecode transformation step in the obfuscation pipeline.
 *
 * Implementations of this interface inspect, modify, or rewrite class nodes
 * and resources present in the provided [ObfuscationContext].
 */
interface Transformer {
  /**
   * The unique identifier for this transformer.
   *
   * Typically used for identification, logging, and configuration purposes.
   */
  val id: String

  /**
   * The execution order/priority of this transformer in the pipeline.
   *
   * Transformers with lower order values are executed before transformers
   * with higher values. Defaults to `0`.
   */
  val order: Int get() = 0

  /**
   * Executes the transformation on the provided [context].
   *
   * @param context The obfuscation context containing the target classes and resources.
   */
  fun transform(context: ObfuscationContext)
}
