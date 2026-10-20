package com.peco2282.obfaryn.transformer

import com.peco2282.obfaryn.api.transformer.NumberObfuscationStrategy
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.util.Random

/** The standard three integer expressions; floating-point values use the API's raw-bit defaults. */
class DefaultNumberObfuscationStrategy : NumberObfuscationStrategy {
  override fun generateInt(value: Int, random: Random) = InsnList().apply {
    val a = random.nextInt()
    val b = random.nextInt()
    when (random.nextInt(3)) {
      0 -> { add(LdcInsnNode(a)); add(LdcInsnNode(value xor a)); add(InsnNode(Opcodes.IXOR)) }
      1 -> { add(LdcInsnNode(a)); add(LdcInsnNode(value - a)); add(InsnNode(Opcodes.IADD)) }
      else -> { add(LdcInsnNode(a)); add(LdcInsnNode(b)); add(InsnNode(Opcodes.IXOR)); add(LdcInsnNode(value - (a xor b))); add(InsnNode(Opcodes.IADD)) }
    }
  }

  override fun generateLong(value: Long, random: Random) = InsnList().apply {
    val a = random.nextLong()
    val b = random.nextLong()
    when (random.nextInt(3)) {
      0 -> { add(LdcInsnNode(a)); add(LdcInsnNode(value xor a)); add(InsnNode(Opcodes.LXOR)) }
      1 -> { add(LdcInsnNode(a)); add(LdcInsnNode(value - a)); add(InsnNode(Opcodes.LADD)) }
      else -> { add(LdcInsnNode(a)); add(LdcInsnNode(b)); add(InsnNode(Opcodes.LXOR)); add(LdcInsnNode(value - (a xor b))); add(InsnNode(Opcodes.LADD)) }
    }
  }
}