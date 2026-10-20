package com.peco2282.obfaryn.transformer

import com.peco2282.obfaryn.api.transformer.StringEncryptionCodec
import com.peco2282.obfaryn.api.transformer.StringEncryptionResult
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.nio.charset.StandardCharsets
import java.util.Base64

class XorStringEncryptionCodec : StringEncryptionCodec {
  override fun encrypt(value: String, key: Int): StringEncryptionResult {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor (key + i * 31)).toByte()
    return StringEncryptionResult(Base64.getEncoder().encodeToString(bytes), key)
  }

  override fun generateDecryptor(owner: String, method: MethodNode) {
    val l0 = LabelNode()
    val l1 = LabelNode()
    method.instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Base64", "getDecoder", "()Ljava/util/Base64\$Decoder;", false))
    method.instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
    method.instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Base64\$Decoder", "decode", "(Ljava/lang/String;)[B", false))
    method.instructions.add(VarInsnNode(Opcodes.ASTORE, 2))
    method.instructions.add(InsnNode(Opcodes.ICONST_0))
    method.instructions.add(VarInsnNode(Opcodes.ISTORE, 3))
    method.instructions.add(l0)
    method.instructions.add(FrameNode(Opcodes.F_NEW, 4, arrayOf("java/lang/String", Opcodes.INTEGER, "[B", Opcodes.INTEGER), 0, null))
    method.instructions.add(VarInsnNode(Opcodes.ILOAD, 3))
    method.instructions.add(VarInsnNode(Opcodes.ALOAD, 2))
    method.instructions.add(InsnNode(Opcodes.ARRAYLENGTH))
    method.instructions.add(JumpInsnNode(Opcodes.IF_ICMPGE, l1))
    method.instructions.add(VarInsnNode(Opcodes.ALOAD, 2))
    method.instructions.add(VarInsnNode(Opcodes.ILOAD, 3))
    method.instructions.add(InsnNode(Opcodes.DUP2))
    method.instructions.add(InsnNode(Opcodes.BALOAD))
    method.instructions.add(VarInsnNode(Opcodes.ILOAD, 1))
    method.instructions.add(VarInsnNode(Opcodes.ILOAD, 3))
    method.instructions.add(IntInsnNode(Opcodes.BIPUSH, 31))
    method.instructions.add(InsnNode(Opcodes.IMUL))
    method.instructions.add(InsnNode(Opcodes.IADD))
    method.instructions.add(InsnNode(Opcodes.IXOR))
    method.instructions.add(InsnNode(Opcodes.BASTORE))
    method.instructions.add(IincInsnNode(3, 1))
    method.instructions.add(JumpInsnNode(Opcodes.GOTO, l0))
    method.instructions.add(l1)
    method.instructions.add(FrameNode(Opcodes.F_NEW, 4, arrayOf("java/lang/String", Opcodes.INTEGER, "[B", Opcodes.INTEGER), 0, null))
    method.instructions.add(TypeInsnNode(Opcodes.NEW, "java/lang/String"))
    method.instructions.add(InsnNode(Opcodes.DUP))
    method.instructions.add(VarInsnNode(Opcodes.ALOAD, 2))
    method.instructions.add(FieldInsnNode(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8", "Ljava/nio/charset/Charset;"))
    method.instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V", false))
    method.instructions.add(InsnNode(Opcodes.ARETURN))
    method.maxLocals = 4
    method.maxStack = 6
  }
}