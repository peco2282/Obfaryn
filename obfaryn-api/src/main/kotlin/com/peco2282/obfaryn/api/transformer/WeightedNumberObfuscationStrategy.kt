package com.peco2282.obfaryn.api.transformer

import org.objectweb.asm.tree.InsnList
import java.util.Random

/** A positive relative selection weight. */
data class WeightedNumberStrategy(val strategy: NumberObfuscationStrategy, val weight: Int = 1) {
  init { require(weight > 0) { "Strategy weight must be positive" } }
}

/**
 * Selects one strategy per constant using the host random source, including float/double overrides.
 * A selected strategy returning null skips that constant; no fallback is attempted.
 * Entries are snapshotted. Strategies must obey the usual fresh-instruction contract.
 */
class WeightedNumberObfuscationStrategy(entries: List<WeightedNumberStrategy>) : NumberObfuscationStrategy {
  private val entries = entries.toList()
  private val total = this.entries.sumOf { it.weight.toLong() }.also {
    require(it in 1..Int.MAX_VALUE.toLong()) { "Total strategy weight must be in 1..Int.MAX_VALUE" }
  }.toInt()

  constructor(vararg strategies: NumberObfuscationStrategy) : this(strategies.map { WeightedNumberStrategy(it) })

  private fun select(random: Random): NumberObfuscationStrategy {
    var ticket = random.nextInt(total)
    for (entry in entries) {
      if (ticket < entry.weight) return entry.strategy
      ticket -= entry.weight
    }
    error("Invalid strategy weights")
  }

  override fun generateInt(value: Int, random: Random): InsnList? = select(random).generateInt(value, random)
  override fun generateLong(value: Long, random: Random): InsnList? = select(random).generateLong(value, random)
  override fun generateFloat(value: Float, random: Random): InsnList? = select(random).generateFloat(value, random)
  override fun generateDouble(value: Double, random: Random): InsnList? = select(random).generateDouble(value, random)
}
