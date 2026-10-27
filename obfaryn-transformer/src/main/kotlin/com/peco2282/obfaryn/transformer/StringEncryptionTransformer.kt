package com.peco2282.obfaryn.transformer

import com.peco2282.obfaryn.api.protection.BodyFeature
import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.transformer.StringEncryptionCodec
import com.peco2282.obfaryn.api.transformer.Transformer
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.util.Random

class StringEncryptionTransformer(
  private val random: Random,
  private val codec: StringEncryptionCodec = RollingStringEncryptionCodec(),
) : Transformer {
  override val isolatedBodyFeature = BodyFeature.STRINGS
  override val id = "string-encryption"
  override val order = 20
  var count = 0
    private set

  override fun skipReason(context: ObfuscationContext, owner: org.objectweb.asm.tree.ClassNode,
    method: MethodNode): String = when {
    owner.version and 0xffff < Opcodes.V1_8 -> "requires-java8-classfile"
    owner.access and (Opcodes.ACC_ANNOTATION or Opcodes.ACC_MODULE) != 0 -> "unsupported-class-kind"
    else -> "no-eligible-string-literals"
  }

  override fun transform(context: ObfuscationContext) {
    count = 0
    for (node in context.classes.values) {
      if (node.version < Opcodes.V1_8 || node.access and Opcodes.ACC_ANNOTATION != 0 || node.access and Opcodes.ACC_MODULE != 0) continue
      val helper = uniqueName(node)
      var accessor: String
      do { accessor = uniqueName(node) } while (accessor == helper)
      var cacheName: String
      do { cacheName = "v\$" + java.lang.Long.toUnsignedString(random.nextLong(), 36) }
      while (node.fields.any { it.name == cacheName })
      val largeHelpers = mutableListOf<MethodNode>()
      val reserved = mutableSetOf(helper, accessor)
      var slots = 0
      for (method in node.methods) {
        if (!context.transformationPolicy.canTransformBody(node, method, BodyFeature.STRINGS)) continue
        desugarStringConcats(method)
        for (insn in method.instructions.toArray()) {
          if (insn !is LdcInsnNode || insn.cst !is String) continue
          val value = insn.cst as String
          val key = random.nextInt()
          val encrypted = codec.encrypt(value, key)
          val replacement = InsnList()
          val slot = slots++
          val inInterface = node.access and Opcodes.ACC_INTERFACE != 0
          if (modifiedUtf8Size(encrypted.payload) <= 65535) {
            replacement.add(LdcInsnNode(encrypted.payload))
            replacement.add(LdcInsnNode(encrypted.key))
            replacement.add(LdcInsnNode(slot))
            replacement.add(MethodInsnNode(Opcodes.INVOKESTATIC, node.name, accessor,
              "(Ljava/lang/String;II)Ljava/lang/String;", inInterface))
          } else {
            var name: String
            do { name = uniqueName(node) } while (!reserved.add(name))
            largeHelpers += largeLiteralAccessor(node.name, name, accessor, cacheName,
              encrypted.payload, encrypted.key, slot, inInterface)
            replacement.add(MethodInsnNode(Opcodes.INVOKESTATIC, node.name, name, "()Ljava/lang/String;", inInterface))
          }
          method.instructions.insertBefore(insn, replacement)
          method.instructions.remove(insn)
          count++
        }
      }
      if (slots > 0) {
        val inInterface = node.access and Opcodes.ACC_INTERFACE != 0
        val access = (if (inInterface) Opcodes.ACC_PUBLIC else Opcodes.ACC_PRIVATE) or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC
        val decryptor = MethodNode(access, helper, "(Ljava/lang/String;I)Ljava/lang/String;", null, null).also {
          codec.generateDecryptor(node.name, it)
          require(it.name == helper && it.desc == "(Ljava/lang/String;I)Ljava/lang/String;" && it.access == access) {
            "StringEncryptionCodec must preserve the decryptor name, descriptor and access flags"
          }
          require(it.instructions.size() > 0) { "StringEncryptionCodec must generate a decryptor body" }
        }
        node.methods.addAll(largeHelpers)
        node.methods.add(decryptor)
        node.methods.add(cachedAccessor(node.name, accessor, helper, cacheName, access, inInterface))
        val fieldAccess = (if (inInterface) Opcodes.ACC_PUBLIC else Opcodes.ACC_PRIVATE) or
          Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC
        node.fields.add(FieldNode(fieldAccess, cacheName, "Ljava/util/concurrent/atomic/AtomicReferenceArray;", null, null))
        val initializer = node.methods.firstOrNull { it.name == "<clinit>" } ?: MethodNode(
          Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).also {
          it.instructions.add(InsnNode(Opcodes.RETURN))
          node.methods.add(it)
        }
        val setup = InsnList().apply {
          add(TypeInsnNode(Opcodes.NEW, "java/util/concurrent/atomic/AtomicReferenceArray"))
          add(InsnNode(Opcodes.DUP))
          add(LdcInsnNode(slots))
          add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/concurrent/atomic/AtomicReferenceArray", "<init>", "(I)V", false))
          add(FieldInsnNode(Opcodes.PUTSTATIC, node.name, cacheName, "Ljava/util/concurrent/atomic/AtomicReferenceArray;"))
        }
        initializer.instructions.insert(setup)
        initializer.maxStack = maxOf(initializer.maxStack, 3)
      }
    }
  }

  private fun modifiedUtf8Size(value: String): Long = value.sumOf { character ->
    when (character.code) { in 1..127 -> 1L; in 0..2047 -> 2L; else -> 3L }
  }

  /** Oversized payloads are assembled only on a cache miss, never on repeated reads. */
  private fun largeLiteralAccessor(owner: String, name: String, accessor: String, field: String,
    payload: String, key: Int, slot: Int, inInterface: Boolean): MethodNode {
    val access = (if (inInterface) Opcodes.ACC_PUBLIC else Opcodes.ACC_PRIVATE) or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC
    return MethodNode(access, name, "()Ljava/lang/String;", null, null).apply {
      val miss = LabelNode()
      // A supertype initializer can call this method before our <clinit> has run.
      instructions.add(InsnNode(Opcodes.ACONST_NULL))
      instructions.add(VarInsnNode(Opcodes.ASTORE, 0))
      instructions.add(FieldInsnNode(Opcodes.GETSTATIC, owner, field, "Ljava/util/concurrent/atomic/AtomicReferenceArray;"))
      instructions.add(JumpInsnNode(Opcodes.IFNULL, miss))
      instructions.add(FieldInsnNode(Opcodes.GETSTATIC, owner, field, "Ljava/util/concurrent/atomic/AtomicReferenceArray;"))
      instructions.add(LdcInsnNode(slot))
      instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/atomic/AtomicReferenceArray", "get", "(I)Ljava/lang/Object;", false))
      instructions.add(VarInsnNode(Opcodes.ASTORE, 0))
      instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
      instructions.add(JumpInsnNode(Opcodes.IFNULL, miss))
      instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
      instructions.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"))
      instructions.add(InsnNode(Opcodes.ARETURN))
      instructions.add(miss)
      instructions.add(FrameNode(Opcodes.F_NEW, 1, arrayOf<Any>("java/lang/Object"), 0, null))
      instructions.add(TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"))
      instructions.add(InsnNode(Opcodes.DUP))
      instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false))
      // Every UTF-16 code unit occupies at most three bytes in a CONSTANT_Utf8 entry.
      for (chunk in payload.chunked(20000)) {
        instructions.add(LdcInsnNode(chunk))
        instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false))
      }
      instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false))
      instructions.add(LdcInsnNode(key))
      instructions.add(LdcInsnNode(slot))
      instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, owner, accessor, "(Ljava/lang/String;II)Ljava/lang/String;", inInterface))
      instructions.add(InsnNode(Opcodes.ARETURN))
      maxLocals = 1
      maxStack = 3
    }
  }

  /** Cache slots are fixed at build time; generated calls cannot grow the cache. */
  private fun cachedAccessor(owner: String, name: String, decoder: String, field: String,
    access: Int, inInterface: Boolean): MethodNode =
    MethodNode(access, name, "(Ljava/lang/String;II)Ljava/lang/String;", null, null).apply {
      val miss = LabelNode()
      fun cache() = instructions.add(FieldInsnNode(Opcodes.GETSTATIC, owner, field,
        "Ljava/util/concurrent/atomic/AtomicReferenceArray;"))
      fun read() {
        cache()
        instructions.add(VarInsnNode(Opcodes.ILOAD, 2))
        instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/atomic/AtomicReferenceArray",
          "get", "(I)Ljava/lang/Object;", false))
      }
      val initialized = LabelNode()
      cache()
      instructions.add(JumpInsnNode(Opcodes.IFNONNULL, initialized))
      // Preserve LDC semantics during supertype initialization without depending on our cache.
      instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
      instructions.add(VarInsnNode(Opcodes.ILOAD, 1))
      instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, owner, decoder,
        "(Ljava/lang/String;I)Ljava/lang/String;", inInterface))
      instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "intern", "()Ljava/lang/String;", false))
      instructions.add(InsnNode(Opcodes.ARETURN))
      instructions.add(initialized)
      instructions.add(FrameNode(Opcodes.F_NEW, 3,
        arrayOf<Any>("java/lang/String", Opcodes.INTEGER, Opcodes.INTEGER), 0, null))
      read()
      instructions.add(VarInsnNode(Opcodes.ASTORE, 3))
      instructions.add(VarInsnNode(Opcodes.ALOAD, 3))
      instructions.add(JumpInsnNode(Opcodes.IFNULL, miss))
      instructions.add(VarInsnNode(Opcodes.ALOAD, 3))
      instructions.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"))
      instructions.add(InsnNode(Opcodes.ARETURN))
      instructions.add(miss)
      instructions.add(FrameNode(Opcodes.F_NEW, 4,
        arrayOf<Any>("java/lang/String", Opcodes.INTEGER, Opcodes.INTEGER, "java/lang/Object"), 0, null))
      instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
      instructions.add(VarInsnNode(Opcodes.ILOAD, 1))
      instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, owner, decoder,
        "(Ljava/lang/String;I)Ljava/lang/String;", inInterface))
      // LDC strings are interned; retain identity across sites, classes, and concurrent first calls.
      instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "intern", "()Ljava/lang/String;", false))
      instructions.add(VarInsnNode(Opcodes.ASTORE, 4))
      cache()
      instructions.add(VarInsnNode(Opcodes.ILOAD, 2))
      instructions.add(InsnNode(Opcodes.ACONST_NULL))
      instructions.add(VarInsnNode(Opcodes.ALOAD, 4))
      instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/atomic/AtomicReferenceArray",
        "compareAndSet", "(ILjava/lang/Object;Ljava/lang/Object;)Z", false))
      instructions.add(InsnNode(Opcodes.POP))
      read()
      instructions.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"))
      instructions.add(InsnNode(Opcodes.ARETURN))
      maxLocals = 5
      maxStack = 4
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
