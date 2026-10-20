package com.peco2282.obfaryn.transformer

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.transformer.StringEncryptionCodec
import com.peco2282.obfaryn.api.transformer.Transformer
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.util.Random

class StringEncryptionTransformer(
  private val random: Random,
  private val codec: StringEncryptionCodec = XorStringEncryptionCodec(),
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
          val key = random.nextInt()
          val encrypted = codec.encrypt(value, key)
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
      if (used) {
        val inInterface = node.access and Opcodes.ACC_INTERFACE != 0
        val access = (if (inInterface) Opcodes.ACC_PUBLIC else Opcodes.ACC_PRIVATE) or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC
        val decryptor = MethodNode(access, helper, "(Ljava/lang/String;I)Ljava/lang/String;", null, null).also {
          codec.generateDecryptor(node.name, it)
          require(it.name == helper && it.desc == "(Ljava/lang/String;I)Ljava/lang/String;" && it.access == access) {
            "StringEncryptionCodec must preserve the decryptor name, descriptor and access flags"
          }
          require(it.instructions.size() > 0) { "StringEncryptionCodec must generate a decryptor body" }
        }
        node.methods.add(decryptor)
      }
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

}
