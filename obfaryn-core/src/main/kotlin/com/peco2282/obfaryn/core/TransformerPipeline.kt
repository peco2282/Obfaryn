package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.api.transformer.TransformerRegistry

/**
 * Mutable, ID-keyed registry used to configure the passes for one invocation.
 *
 * IDs must be non-blank and unique. Disabling or replacing an absent ID fails immediately;
 * dependency existence and cycles are checked only after all configuration callbacks finish.
 * Execution repeatedly selects the first dependency-ready pass from ascending [Transformer.order]
 * and then ID order. Dependencies therefore take precedence over numeric order, and registration
 * order does not break ties. Disabling a pass does not automatically disable its dependants.
 */
class TransformerPipeline internal constructor(defaults: List<Transformer>) : TransformerRegistry {
  private val passes = linkedMapOf<String, Transformer>()

  init {
    defaults.forEach(::add)
  }

  override operator fun get(id: String): Transformer? = passes[id]

  /** Registers [transformer] without replacing an existing pass with the same ID. */
  override fun add(transformer: Transformer) {
    require(transformer.id.isNotBlank()) { "Transformer ID must not be blank" }
    require(transformer.id !in passes) { "Duplicate transformer: ${transformer.id}" }
    passes[transformer.id] = transformer
  }

  /** Removes [id]; a later [add] may register that ID again. */
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
