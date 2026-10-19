package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.api.transformer.TransformerRegistry

/** Edits a fresh set of built-in passes for each invocation. Unknown IDs fail fast. */
class TransformerPipeline internal constructor(defaults: List<Transformer>) : TransformerRegistry {
  private val passes = linkedMapOf<String, Transformer>()

  init {
    defaults.forEach(::add)
  }

  override operator fun get(id: String): Transformer? = passes[id]

  override fun add(transformer: Transformer) {
    require(transformer.id.isNotBlank()) { "Transformer ID must not be blank" }
    require(transformer.id !in passes) { "Duplicate transformer: ${transformer.id}" }
    passes[transformer.id] = transformer
  }

  override fun disable(id: String) {
    require(passes.remove(id) != null) { "Unknown or disabled transformer: $id" }
  }

  /** Replacement retains the module ID; its order and requirements belong to the replacement. */
  override fun replace(id: String, transformer: Transformer) {
    require(id in passes) { "Unknown or disabled transformer: $id" }
    require(transformer.id == id) { "Replacement must retain transformer ID $id" }
    passes[id] = transformer
  }

  internal fun ordered(): List<Transformer> {
    val remaining = passes.values.sortedWith(compareBy<Transformer> { it.order }.thenBy { it.id }).toMutableList()
    for (pass in remaining) require(pass.requires.all { it in passes }) {
      "Missing dependencies for ${pass.id}: ${pass.requires - passes.keys}"
    }
    val result = mutableListOf<Transformer>()
    val completed = mutableSetOf<String>()
    while (remaining.isNotEmpty()) {
      val next = remaining.firstOrNull { completed.containsAll(it.requires) }
      require(next != null) { "Cyclic transformer dependencies: ${remaining.map { it.id }}" }
      result += next
      completed += next.id
      remaining.remove(next)
    }
    return result
  }
}
