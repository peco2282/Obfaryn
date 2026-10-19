package com.peco2282.obfaryn.api.transformer

/**
 * Mutable pipeline registration window exposed to a [PipelineConfigurer].
 *
 * IDs are non-blank and unique, and replacements must retain the ID of the pass they replace. Registration
 * configures which passes run; it does not execute them. Dependencies in [Transformer.requires]
 * must still be satisfied after all additions, replacements, and disabling operations.
 */
interface TransformerRegistry {
  /** Returns the currently registered pass with [id], or `null` if no such pass is registered. */
  operator fun get(id: String): Transformer?

  /**
   * Registers [transformer] under its ID, which must not already belong to a registered pass.
   *
   * @throws IllegalArgumentException In the core registry, if the ID is blank or already registered.
   */
  fun add(transformer: Transformer)

  /**
   * Removes the pass identified by [id]; the ID may be registered again later.
   * Dependent passes are not automatically disabled.
   *
   * @throws IllegalArgumentException In the core registry, if [id] is unknown or already disabled.
   */
  fun disable(id: String)

  /**
   * Replaces an existing pass at [id]; [transformer] must have the same ID.
   *
   * @throws IllegalArgumentException In the core registry, if [id] is absent or the replacement ID differs.
   */
  fun replace(id: String, transformer: Transformer)

  /** Equivalent to [add], for use in receiver-style configuration blocks. */
  operator fun plusAssign(transformer: Transformer) = add(transformer)
}

/**
 * Customizes a fresh transformer registry for each obfuscation run.
 *
 * Create stateful transformers inside [configure], rather than sharing an instance across runs.
 * The configuration callback may be reused even though each registry is new.
 * The core host registers its selected initial passes before invoking configuration callbacks,
 * so they can be inspected, disabled, or replaced. Earlier callbacks' changes are visible to later ones.
 */
interface PipelineConfigurer {
  /** Adds, disables, or replaces passes in [registry] before dependency validation and execution. */
  fun configure(registry: TransformerRegistry)

  companion object {
    /**
     * Wraps a receiver-style registry configuration without exposing the execution engine.
     *
     * For example, `PipelineConfigurer { add(MyTransformer()) }` constructs a new pass whenever
     * the callback is applied, provided construction stays inside the block.
     *
     * @param configure Block applied to each run's fresh registry.
     * @return A callback that applies [configure] when the host configures a pipeline.
     */
    operator fun invoke(configure: TransformerRegistry.() -> Unit): PipelineConfigurer =
      object : PipelineConfigurer {
        override fun configure(registry: TransformerRegistry) = registry.configure()
      }
  }
}
