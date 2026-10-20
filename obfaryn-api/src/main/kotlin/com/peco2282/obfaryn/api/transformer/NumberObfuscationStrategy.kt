package com.peco2282.obfaryn.api.transformer

import com.peco2282.obfaryn.api.ObfuscationContext
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MethodInsnNode
import java.util.Random

/**
 * Selection-only input. Inspect annotations on [owner] and [method]; do not mutate ASM nodes.
 *
 * @property context Current execution context, including scope and transformation policy.
 * @property owner Class containing the candidate instruction.
 * @property method Method containing the candidate instruction.
 * @property instruction Original numeric constant instruction, still owned by the method's list.
 * @property value Decoded constant, represented as Int, Long, Float, or Double.
 */
data class NumberObfuscationSite(
  val context: ObfuscationContext,
  val owner: ClassNode,
  val method: MethodNode,
  val instruction: AbstractInsnNode,
  val value: Number,
)

/**
 * Generates expressions, independently of constant discovery and target selection.
 * Return fresh, non-empty instructions, or null to leave a constant unchanged.
 * The expression must preserve the existing stack and locals, leave exactly one value of
 * the original JVM type, and provide valid frames for any branches. Runtime dependencies
 * must be available in the output application; implementations are not bundled.
 * Floating-point defaults reuse integer expressions and preserve raw bits, including NaNs
 * and signed zero. Override them only when a dedicated floating-point expression is needed.
 */
interface NumberObfuscationStrategy {
  /**
   * Produces an expression leaving [value] as one JVM int (one stack slot).
   * @param random Host-supplied random source; use this rather than global randomness for seeded runs.
   * @return Fresh instructions owned by no other list, or `null` to retain the literal.
   */
  fun generateInt(value: Int, random: Random): InsnList?

  /**
   * Produces an expression leaving [value] as one JVM long (two stack slots).
   * @param random Host-supplied random source for reproducible expression generation.
   * @return Fresh instructions owned by no other list, or `null` to retain the literal.
   */
  fun generateLong(value: Long, random: Random): InsnList?

  /**
   * Produces one JVM float. By default, delegates raw bits to [generateInt] and appends
   * `Float.intBitsToFloat`; `null` propagates and an empty expression is rejected.
   * @throws IllegalArgumentException If the delegated expression is empty.
   */
  fun generateFloat(value: Float, random: Random): InsnList? =
    generateInt(value.toRawBits(), random)?.apply {
      require(size() > 0) { "NumberObfuscationStrategy must generate a non-empty expression or return null" }
      add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false))
    }

  /**
   * Produces one JVM double. By default, delegates raw bits to [generateLong] and appends
   * `Double.longBitsToDouble`; `null` propagates and an empty expression is rejected.
   * @throws IllegalArgumentException If the delegated expression is empty.
   */
  fun generateDouble(value: Double, random: Random): InsnList? =
    generateLong(value.toRawBits(), random)?.apply {
      require(size() > 0) { "NumberObfuscationStrategy must generate a non-empty expression or return null" }
      add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false))
    }
}

/** Called only after the host's body transformation policy allows a method. */
fun interface NumberObfuscationSelector {
  /** Combines selectors with short-circuit evaluation, preserving their random source. */
  infix fun and(other: NumberObfuscationSelector): NumberObfuscationSelector =
    NumberObfuscationSelector { site, random -> shouldTransform(site, random) && other.shouldTransform(site, random) }
  operator fun plus(other: NumberObfuscationSelector): NumberObfuscationSelector =
    NumberObfuscationSelector { site, random -> shouldTransform(site, random) && other.shouldTransform(site, random) }


  /**
   * Selects an eligible literal without changing [site]'s nodes.
   * @param random Host-supplied random source, usable for reproducible sampling.
   * @return `true` to request strategy generation; the strategy may still return `null`.
   */
  fun shouldTransform(site: NumberObfuscationSite, random: Random): Boolean

  companion object {
    /** Seeded sampling. Rates 0 and 1 consume no randomness. */
    @JvmStatic
    fun probability(rate: Double): NumberObfuscationSelector {
      require(rate in 0.0..1.0) { "Probability must be in 0..1" }
      return NumberObfuscationSelector { _, random ->
        rate == 1.0 || (rate != 0.0 && random.nextDouble() < rate)
      }
    }
  }
}
