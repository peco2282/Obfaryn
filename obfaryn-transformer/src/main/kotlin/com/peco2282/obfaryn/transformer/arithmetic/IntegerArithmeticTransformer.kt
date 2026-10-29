package com.peco2282.obfaryn.transformer.arithmetic

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.protection.BodyFeature
import com.peco2282.obfaryn.api.transformer.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.*
import java.util.Random

/** Modular JVM int/long identities. Never changes floating point, division, shifts or evaluation order. */
class IntegerArithmeticTransformer @JvmOverloads constructor(
  private val random: Random,
  private val maxOperationsPerMethod: Int = 16,
) : Transformer {
  init { require(maxOperationsPerMethod >= 0) }
  override val isolatedBodyFeature = BodyFeature.INTEGER_ARITHMETIC
  override val transformedCount: Int get() = count
  override val id = "integer-arithmetic"
  override val order = 32
  override val requiresFrameRecomputation = true
  var count = 0
    private set
  override fun report() = TransformerReport(id, mapOf("transformed" to count.toLong()))

  override fun skipReason(context: ObfuscationContext, owner: org.objectweb.asm.tree.ClassNode,
    method: MethodNode): String = when {
    !context.transformationInputs.isApplicationClass(owner.name) -> "outside-application-scope"
    maxOperationsPerMethod == 0 -> "arithmetic-operation-budget-zero"
    method.name.startsWith('<') -> "constructor-or-class-initializer"
    method.access and (ACC_ABSTRACT or ACC_NATIVE or ACC_SYNTHETIC) != 0 -> "no-supported-bytecode-body"
    else -> "no-supported-integer-operations"
  }

  override fun transform(context: ObfuscationContext) {
    count = 0
    context.forEachTransformableMethod(BodyFeature.INTEGER_ARITHMETIC) { owner, method ->
      if (!context.transformationInputs.isApplicationClass(owner.name)) return@forEachTransformableMethod
      if (method.name.startsWith('<') || method.access and (ACC_ABSTRACT or ACC_NATIVE or ACC_SYNTHETIC) != 0)
        return@forEachTransformableMethod
      var operations = 0
      // Snapshot prevents recursive expansion of our own generated arithmetic.
      for (instruction in method.instructions.toArray()) {
        if (operations >= maxOperationsPerMethod) break
        val wide = instruction.opcode in setOf(LADD, LSUB, LXOR)
        if (instruction.opcode !in setOf(IADD, ISUB, IXOR, LADD, LSUB, LXOR)) continue
        val key = if (wide) random.nextLong() else random.nextInt().toLong()
        val code = InsnList()
        // Only the top operand is transformed, so side effects and operand evaluation stay ordered.
        fun constant(value: Long) { code.add(LdcInsnNode(if (wide) value else value.toInt())) }
        when (instruction.opcode) {
          IADD, LADD -> { // a + b == (a + (b + k)) - k, with JVM modular overflow.
            constant(key); code.add(InsnNode(if (wide) LADD else IADD))
            code.add(InsnNode(instruction.opcode)); constant(key); code.add(InsnNode(if (wide) LSUB else ISUB))
          }
          ISUB, LSUB -> { // a - b == (a - (b + k)) + k.
            constant(key); code.add(InsnNode(if (wide) LADD else IADD))
            code.add(InsnNode(instruction.opcode)); constant(key); code.add(InsnNode(if (wide) LADD else IADD))
          }
          else -> { // a xor b == (a xor (b xor k)) xor k.
            constant(key); code.add(InsnNode(instruction.opcode))
            code.add(InsnNode(instruction.opcode)); constant(key); code.add(InsnNode(instruction.opcode))
          }
        }
        method.instructions.insertBefore(instruction, code)
        method.instructions.remove(instruction)
        operations++
        count++
      }
    }
  }
}
