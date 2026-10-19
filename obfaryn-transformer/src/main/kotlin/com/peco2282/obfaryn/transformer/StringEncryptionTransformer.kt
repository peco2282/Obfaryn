package com.peco2282.obfaryn.transformer

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.transformer.Transformer
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Random

data class EncryptedString(val payload: String, val key: Int)

interface StringEncryptionStrategy {
  fun encrypt(value: String, key: Int): EncryptedString
}

class XorStringEncryptionStrategy : StringEncryptionStrategy {
  override fun encrypt(value: String, key: Int): EncryptedString {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor (key + i * 31)).toByte()
    return EncryptedString(Base64.getEncoder().encodeToString(bytes), key)
  }
}

class StringEncryptionTransformer(
  private val random: Random,
  private val strategy: StringEncryptionStrategy = XorStringEncryptionStrategy(),
) : Transformer {
  override val id = "string-encryption"
  override val order = 20
  var count = 0
    private set

  override fun transform(context: ObfuscationContext) {
    for (node in context.classes.values) {
      if (node.version < Opcodes.V1_8 || node.access and Opcodes.ACC_ANNOTATION != 0 || node.access and Opcodes.ACC_MODULE != 0) continue
      val helper = uniqueName(node)
      var used = false
      for (method in node.methods) {
        if (!context.transformationPolicy.canTransformBody(node, method)) continue
        desugarStringConcats(method)
        for (insn in method.instructions.toArray()) {
          if (insn !is LdcInsnNode || insn.cst !is String) continue
          val value = insn.cst as String
          val encrypted = strategy.encrypt(value, random.nextInt())
          val replacement = InsnList()
          replacement.add(LdcInsnNode(encrypted.payload))
          replacement.add(LdcInsnNode(encrypted.key))
          replacement.add(MethodInsnNode(Opcodes.INVOKESTATIC, node.name, helper, "(Ljava/lang/String;I)Ljava/lang/String;", node.access and Opcodes.ACC_INTERFACE != 0))
          method.instructions.insertBefore(insn, replacement)
          method.instructions.remove(insn)
          count++
          used = true
        }
      }
      if (used) node.methods.add(makeHelper(helper, node.access and Opcodes.ACC_INTERFACE != 0))
    }
  }

  /** Moves StringConcatFactory recipe literals into ordinary LDC instructions so they are encrypted below. */
  private fun desugarStringConcats(method: MethodNode) {
    for (indy in method.instructions.toArray().filterIsInstance<InvokeDynamicInsnNode>()) {
      if (indy.bsm.owner != "java/lang/invoke/StringConcatFactory" ||
        indy.bsm.name !in setOf("makeConcat", "makeConcatWithConstants")) continue
      val arguments = Type.getArgumentTypes(indy.desc)
      val slots = IntArray(arguments.size)
      var nextLocal = method.maxLocals
      for (index in arguments.indices) { slots[index] = nextLocal; nextLocal += arguments[index].size }
      method.maxLocals = nextLocal
      val replacement = InsnList()
      for (index in arguments.indices.reversed())
        replacement.add(VarInsnNode(arguments[index].getOpcode(Opcodes.ISTORE), slots[index]))
      replacement.add(TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"))
      replacement.add(InsnNode(Opcodes.DUP))
      replacement.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false))

      fun append(type: Type) {
        val descriptor = when (type.sort) {
          Type.BOOLEAN -> "(Z)Ljava/lang/StringBuilder;"
          Type.CHAR -> "(C)Ljava/lang/StringBuilder;"
          Type.BYTE, Type.SHORT, Type.INT -> "(I)Ljava/lang/StringBuilder;"
          Type.LONG -> "(J)Ljava/lang/StringBuilder;"
          Type.FLOAT -> "(F)Ljava/lang/StringBuilder;"
          Type.DOUBLE -> "(D)Ljava/lang/StringBuilder;"
          Type.OBJECT -> if (type.internalName == "java/lang/String") "(Ljava/lang/String;)Ljava/lang/StringBuilder;"
            else "(Ljava/lang/Object;)Ljava/lang/StringBuilder;"
          else -> "(Ljava/lang/Object;)Ljava/lang/StringBuilder;"
        }
        replacement.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", descriptor, false))
      }
      fun literal(value: String) {
        if (value.isEmpty()) return
        replacement.add(LdcInsnNode(value)); append(Type.getType(String::class.java))
      }
      fun constant(value: Any) {
        replacement.add(LdcInsnNode(value))
        append(when (value) {
          is Int -> Type.INT_TYPE
          is Long -> Type.LONG_TYPE
          is Float -> Type.FLOAT_TYPE
          is Double -> Type.DOUBLE_TYPE
          is String -> Type.getType(String::class.java)
          else -> Type.getType(Any::class.java)
        })
      }

      if (indy.bsm.name == "makeConcat") {
        for (index in arguments.indices) {
          replacement.add(VarInsnNode(arguments[index].getOpcode(Opcodes.ILOAD), slots[index])); append(arguments[index])
        }
      } else {
        val recipe = indy.bsmArgs.firstOrNull() as? String ?: continue
        var argumentIndex = 0
        var constantIndex = 1
        val text = StringBuilder()
        fun flush() { literal(text.toString()); text.setLength(0) }
        for (character in recipe) when (character) {
          '\u0001' -> {
            flush()
            if (argumentIndex >= arguments.size) continue
            replacement.add(VarInsnNode(arguments[argumentIndex].getOpcode(Opcodes.ILOAD), slots[argumentIndex]))
            append(arguments[argumentIndex++])
          }
          '\u0002' -> { flush(); if (constantIndex < indy.bsmArgs.size) constant(indy.bsmArgs[constantIndex++]) }
          else -> text.append(character)
        }
        flush()
        if (argumentIndex != arguments.size || constantIndex != indy.bsmArgs.size) continue
      }
      replacement.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false))
      method.instructions.insertBefore(indy, replacement)
      method.instructions.remove(indy)
    }
  }

  private fun uniqueName(node: ClassNode): String {
    var name: String
    do { name = "v\$" + java.lang.Long.toUnsignedString(random.nextLong(), 36) } while (node.methods.any { it.name == name })
    return name
  }

  private fun makeHelper(name: String, inInterface: Boolean): MethodNode {
    val visibility = if (inInterface) Opcodes.ACC_PUBLIC else Opcodes.ACC_PRIVATE
    val m = MethodNode(visibility or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC, name, "(Ljava/lang/String;I)Ljava/lang/String;", null, null)
    val l0 = LabelNode()
    val l1 = LabelNode()
    m.instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Base64", "getDecoder", "()Ljava/util/Base64\$Decoder;", false))
    m.instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
    m.instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Base64\$Decoder", "decode", "(Ljava/lang/String;)[B", false))
    m.instructions.add(VarInsnNode(Opcodes.ASTORE, 2))
    m.instructions.add(InsnNode(Opcodes.ICONST_0))
    m.instructions.add(VarInsnNode(Opcodes.ISTORE, 3))
    m.instructions.add(l0)
    m.instructions.add(FrameNode(Opcodes.F_NEW, 4, arrayOf("java/lang/String", Opcodes.INTEGER, "[B", Opcodes.INTEGER), 0, null))
    m.instructions.add(VarInsnNode(Opcodes.ILOAD, 3))
    m.instructions.add(VarInsnNode(Opcodes.ALOAD, 2))
    m.instructions.add(InsnNode(Opcodes.ARRAYLENGTH))
    m.instructions.add(JumpInsnNode(Opcodes.IF_ICMPGE, l1))
    m.instructions.add(VarInsnNode(Opcodes.ALOAD, 2))
    m.instructions.add(VarInsnNode(Opcodes.ILOAD, 3))
    m.instructions.add(InsnNode(Opcodes.DUP2))
    m.instructions.add(InsnNode(Opcodes.BALOAD))
    m.instructions.add(VarInsnNode(Opcodes.ILOAD, 1))
    m.instructions.add(VarInsnNode(Opcodes.ILOAD, 3))
    m.instructions.add(IntInsnNode(Opcodes.BIPUSH, 31))
    m.instructions.add(InsnNode(Opcodes.IMUL))
    m.instructions.add(InsnNode(Opcodes.IADD))
    m.instructions.add(InsnNode(Opcodes.IXOR))
    m.instructions.add(InsnNode(Opcodes.BASTORE))
    m.instructions.add(IincInsnNode(3, 1))
    m.instructions.add(JumpInsnNode(Opcodes.GOTO, l0))
    m.instructions.add(l1)
    m.instructions.add(FrameNode(Opcodes.F_NEW, 4, arrayOf("java/lang/String", Opcodes.INTEGER, "[B", Opcodes.INTEGER), 0, null))
    m.instructions.add(TypeInsnNode(Opcodes.NEW, "java/lang/String"))
    m.instructions.add(InsnNode(Opcodes.DUP))
    m.instructions.add(VarInsnNode(Opcodes.ALOAD, 2))
    m.instructions.add(FieldInsnNode(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8", "Ljava/nio/charset/Charset;"))
    m.instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V", false))
    m.instructions.add(InsnNode(Opcodes.ARETURN))
    m.maxLocals = 4
    m.maxStack = 6
    return m
  }
}
