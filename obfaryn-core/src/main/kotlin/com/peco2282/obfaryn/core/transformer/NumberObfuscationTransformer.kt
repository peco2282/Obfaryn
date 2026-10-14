package com.peco2282.obfaryn.core.transformer

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.util.Random

class NumberObfuscationTransformer(private val random: Random) : Transformer {
  override val id = "number-obfuscation"
  override val order = 30
  var count = 0
    private set

  override fun transform(context: ObfuscationContext) {
    for (node in context.classes.values) for (method in node.methods) {
      if (ObfuscationAnnotationPolicy.skipBody(node, method)) continue
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
        // Short constants are frequent in control flow and expanding them adds little value.
        if (value is Int && value in -1..1 || value is Long && value in -1L..1L) continue
        val list = when (value) {
          is Int -> intExpr(value)
          is Long -> longExpr(value)
          is Float -> intExpr(java.lang.Float.floatToRawIntBits(value)).apply {
            add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false))
          }
          is Double -> longExpr(java.lang.Double.doubleToRawLongBits(value)).apply {
            add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false))
          }
          else -> continue
        }
        method.instructions.insertBefore(insn, list)
        method.instructions.remove(insn)
        count++
      }
    }
  }

  private fun intExpr(value: Int) = InsnList().apply {
    val a = random.nextInt()
    val b = random.nextInt()
    when (random.nextInt(3)) {
      0 -> { add(LdcInsnNode(a)); add(LdcInsnNode(value xor a)); add(InsnNode(Opcodes.IXOR)) }
      1 -> { add(LdcInsnNode(a)); add(LdcInsnNode(value - a)); add(InsnNode(Opcodes.IADD)) }
      else -> { add(LdcInsnNode(a)); add(LdcInsnNode(b)); add(InsnNode(Opcodes.IXOR)); add(LdcInsnNode(value - (a xor b))); add(InsnNode(Opcodes.IADD)) }
    }
  }

  private fun longExpr(value: Long) = InsnList().apply {
    val a = random.nextLong()
    val b = random.nextLong()
    when (random.nextInt(3)) {
      0 -> { add(LdcInsnNode(a)); add(LdcInsnNode(value xor a)); add(InsnNode(Opcodes.LXOR)) }
      1 -> { add(LdcInsnNode(a)); add(LdcInsnNode(value - a)); add(InsnNode(Opcodes.LADD)) }
      else -> { add(LdcInsnNode(a)); add(LdcInsnNode(b)); add(InsnNode(Opcodes.LXOR)); add(LdcInsnNode(value - (a xor b))); add(InsnNode(Opcodes.LADD)) }
    }
  }
}
