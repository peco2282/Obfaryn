package com.peco2282.obfaryn.api.transformer

/** Mutable registration window. IDs are unique; replacements must retain their ID. */
interface TransformerRegistry {
  operator fun get(id: String): Transformer?
  fun add(transformer: Transformer)
  fun disable(id: String)
  fun replace(id: String, transformer: Transformer)

  operator fun plusAssign(transformer: Transformer) = add(transformer)
}

/** Applied to a fresh registry for every obfuscation run. Create stateful passes here. */
fun interface PipelineConfigurer {
  fun configure(registry: TransformerRegistry)

  companion object {
    /** Kotlin receiver DSL without exposing the execution engine. */
    operator fun invoke(configure: TransformerRegistry.() -> Unit): PipelineConfigurer =
      PipelineConfigurer { registry -> registry.configure() }
  }
}
