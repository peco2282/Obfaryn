package com.peco2282.obfaryn.transformer

import com.peco2282.obfaryn.api.transformer.NumberObfuscationStrategy
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.util.Random

/** Rotate-keyed integer expressions; floating-point values use the API's raw-bit defaults. */
class DefaultNumberObfuscationStrategy : NumberObfuscationStrategy {
  override fun generateInt(value: Int, random: Random) = InsnList().apply {
    val distance = 1 + random.nextInt(Int.SIZE_BITS - 1)
    val reverse = random.nextBoolean()
    val left = random.nextBoolean()
    val base = if (reverse) Integer.reverseBytes(value) else value
    add(LdcInsnNode(if (left) Integer.rotateRight(base, distance) else Integer.rotateLeft(base, distance)))
    add(LdcInsnNode(distance))
    add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer",
      if (left) "rotateLeft" else "rotateRight", "(II)I", false))
    if (reverse) add(MethodInsnNode(Opcodes.INVOKESTATIC,
      "java/lang/Integer", "reverseBytes", "(I)I", false))
  }

  override fun generateLong(value: Long, random: Random) = InsnList().apply {
    val distance = 1 + random.nextInt(Long.SIZE_BITS - 1)
    val reverse = random.nextBoolean()
    val left = random.nextBoolean()
    val base = if (reverse) java.lang.Long.reverseBytes(value) else value
    add(LdcInsnNode(if (left) java.lang.Long.rotateRight(base, distance) else java.lang.Long.rotateLeft(base, distance)))
    add(LdcInsnNode(distance))
    add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long",
      if (left) "rotateLeft" else "rotateRight", "(JI)J", false))
    if (reverse) add(MethodInsnNode(Opcodes.INVOKESTATIC,
      "java/lang/Long", "reverseBytes", "(J)J", false))
  }
}
