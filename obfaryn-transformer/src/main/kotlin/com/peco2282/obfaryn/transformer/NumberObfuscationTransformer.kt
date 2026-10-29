package com.peco2282.obfaryn.transformer

import com.peco2282.obfaryn.api.protection.BodyFeature
import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.transformer.NumberObfuscationSite
import com.peco2282.obfaryn.api.transformer.NumberObfuscationSelector
import com.peco2282.obfaryn.api.transformer.NumberObfuscationStrategy
import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.api.transformer.forEachTransformableMethod
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.util.Random

class NumberObfuscationTransformer @JvmOverloads constructor(
  private val random: Random,
  private val strategy: NumberObfuscationStrategy = DefaultNumberObfuscationStrategy(),
  private val selector: NumberObfuscationSelector = DefaultNumberObfuscationSelector(),
) : Transformer {
  override val isolatedBodyFeature = BodyFeature.NUMBERS
  override val transformedCount: Int get() = count
  override val id = "number-obfuscation"
  override val order = 30
  var count = 0
    private set

  override fun skipReason(context: ObfuscationContext, owner: org.objectweb.asm.tree.ClassNode,
    method: MethodNode): String = "no-numeric-literals-or-selector-strategy-exclusion"

  override fun transform(context: ObfuscationContext) {
    count = 0
    context.forEachTransformableMethod(BodyFeature.NUMBERS) { node, method ->
      for (insn in method.instructions.toArray()) {
        val value: Number = when (insn) {
          is LdcInsnNode -> insn.cst as? Number ?: continue
          is IntInsnNode -> if (insn.opcode == Opcodes.BIPUSH || insn.opcode == Opcodes.SIPUSH) insn.operand else continue
          is InsnNode -> when (insn.opcode) {
            in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> insn.opcode - Opcodes.ICONST_0
            Opcodes.LCONST_0 -> 0L
            Opcodes.LCONST_1 -> 1L
            Opcodes.FCONST_0 -> 0.0f
            Opcodes.FCONST_1 -> 1.0f
            Opcodes.FCONST_2 -> 2.0f
            Opcodes.DCONST_0 -> 0.0
            Opcodes.DCONST_1 -> 1.0
            else -> continue
          }
          else -> continue
        }
        if (value !is Int && value !is Long && value !is Float && value !is Double) continue
        if (!selector.shouldTransform(NumberObfuscationSite(context, node, method, insn, value), random)) continue
        val list = when (value) {
          is Int -> strategy.generateInt(value, random)
          is Long -> strategy.generateLong(value, random)
          is Float -> strategy.generateFloat(value, random)
          is Double -> strategy.generateDouble(value, random)
          else -> null
        } ?: continue
        require(list.size() > 0) { "NumberObfuscationStrategy must generate a non-empty expression or return null" }
        method.instructions.insertBefore(insn, list)
        method.instructions.remove(insn)
        count++
      }
    }
  }
}
