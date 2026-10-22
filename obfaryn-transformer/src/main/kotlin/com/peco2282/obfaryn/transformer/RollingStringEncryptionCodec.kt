package com.peco2282.obfaryn.transformer

import com.peco2282.obfaryn.api.transformer.StringEncryptionCodec
import com.peco2282.obfaryn.api.transformer.StringEncryptionResult
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.util.Base64

/** Per-literal keyed masking with ciphertext feedback; obfuscation, not cryptographic secrecy. */
class RollingStringEncryptionCodec : StringEncryptionCodec {
  override fun encrypt(value: String, key: Int): StringEncryptionResult {
    val bytes = ByteArray(value.length * 2)
    var state = key xor 0x6A09E667
    value.forEachIndexed { index, character ->
      state = Integer.rotateLeft(state + index + 0x3C6EF372, 13) * -1640531527
      val cipher = (character.code xor state xor (state ushr 16)) and 0xffff
      bytes[index * 2] = (cipher ushr 8).toByte()
      bytes[index * 2 + 1] = cipher.toByte()
      state = Integer.rotateLeft(state xor cipher, 7) + key + index
    }
    return StringEncryptionResult(Base64.getEncoder().encodeToString(bytes), key)
  }

  override fun generateDecryptor(owner: String, method: MethodNode) {
    val code = method.instructions
    fun op(opcode: Int) = code.add(InsnNode(opcode))
    fun load(opcode: Int, slot: Int) = code.add(VarInsnNode(opcode, slot))
    fun constant(value: Int) = code.add(LdcInsnNode(value))
    fun rotate(bits: Int) {
      constant(bits)
      code.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "rotateLeft", "(II)I", false))
    }
    fun frame() = code.add(FrameNode(Opcodes.F_NEW, 6,
      arrayOf<Any>("java/lang/String", Opcodes.INTEGER, "[B", "[C", Opcodes.INTEGER, Opcodes.INTEGER), 0, null))
    code.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Base64", "getDecoder", "()Ljava/util/Base64\$Decoder;", false))
    load(Opcodes.ALOAD, 0)
    code.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Base64\$Decoder", "decode", "(Ljava/lang/String;)[B", false))
    load(Opcodes.ASTORE, 2)
    load(Opcodes.ALOAD, 2); op(Opcodes.ARRAYLENGTH); op(Opcodes.ICONST_1); op(Opcodes.IUSHR)
    code.add(IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_CHAR)); load(Opcodes.ASTORE, 3)
    op(Opcodes.ICONST_0); load(Opcodes.ISTORE, 4)
    load(Opcodes.ILOAD, 1); constant(0x6A09E667); op(Opcodes.IXOR); load(Opcodes.ISTORE, 5)
    val loop = LabelNode(); val done = LabelNode()
    code.add(loop); frame()
    load(Opcodes.ILOAD, 4); load(Opcodes.ALOAD, 3); op(Opcodes.ARRAYLENGTH)
    code.add(JumpInsnNode(Opcodes.IF_ICMPGE, done))
    load(Opcodes.ILOAD, 5); load(Opcodes.ILOAD, 4); op(Opcodes.IADD)
    constant(0x3C6EF372); op(Opcodes.IADD); rotate(13)
    constant(-1640531527); op(Opcodes.IMUL); load(Opcodes.ISTORE, 5)
    load(Opcodes.ALOAD, 2); load(Opcodes.ILOAD, 4); op(Opcodes.ICONST_1); op(Opcodes.ISHL)
    op(Opcodes.BALOAD); constant(255); op(Opcodes.IAND); constant(8); op(Opcodes.ISHL)
    load(Opcodes.ALOAD, 2); load(Opcodes.ILOAD, 4); op(Opcodes.ICONST_1); op(Opcodes.ISHL)
    op(Opcodes.ICONST_1); op(Opcodes.IADD); op(Opcodes.BALOAD); constant(255); op(Opcodes.IAND)
    op(Opcodes.IOR); load(Opcodes.ISTORE, 6)
    load(Opcodes.ALOAD, 3); load(Opcodes.ILOAD, 4); load(Opcodes.ILOAD, 6)
    load(Opcodes.ILOAD, 5); op(Opcodes.IXOR)
    load(Opcodes.ILOAD, 5); constant(16); op(Opcodes.IUSHR); op(Opcodes.IXOR)
    op(Opcodes.I2C); op(Opcodes.CASTORE)
    load(Opcodes.ILOAD, 5); load(Opcodes.ILOAD, 6); op(Opcodes.IXOR); rotate(7)
    load(Opcodes.ILOAD, 1); op(Opcodes.IADD); load(Opcodes.ILOAD, 4); op(Opcodes.IADD)
    load(Opcodes.ISTORE, 5)
    code.add(IincInsnNode(4, 1)); code.add(JumpInsnNode(Opcodes.GOTO, loop))
    code.add(done); frame()
    code.add(TypeInsnNode(Opcodes.NEW, "java/lang/String")); op(Opcodes.DUP); load(Opcodes.ALOAD, 3)
    code.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false))
    op(Opcodes.ARETURN)
    method.maxLocals = 7
    method.maxStack = 6
  }
}
