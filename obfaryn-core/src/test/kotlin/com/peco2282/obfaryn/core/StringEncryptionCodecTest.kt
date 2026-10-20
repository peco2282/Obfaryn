package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.transformer.StringEncryptionCodec
import com.peco2282.obfaryn.api.transformer.StringEncryptionResult
import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.transformer.StringEncryptionTransformer
import com.peco2282.obfaryn.transformer.builder.transformers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.util.Random

class StringEncryptionCodecTest {
  private fun owner(inInterface: Boolean = false) = ClassNode().apply {
    name = "app/Strings"
    version = Opcodes.V1_8
    access = Opcodes.ACC_PUBLIC or if (inInterface) Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT else Opcodes.ACC_SUPER
    superName = "java/lang/Object"
    listOf("", "hello", "日本語\u0000🔐").forEachIndexed { index, value ->
      methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "text$index", "()Ljava/lang/String;", null, null).apply {
        instructions.add(LdcInsnNode(value))
        instructions.add(InsnNode(Opcodes.ARETURN))
        maxStack = 1
      })
    }
  }

  private fun assertRuntime(node: ClassNode) {
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    node.accept(writer)
    val bytes = writer.toByteArray()
    val loaded = object : ClassLoader(null) {
      fun loadOutput() = defineClass(null, bytes, 0, bytes.size)
    }.loadOutput()
    listOf("", "hello", "日本語\u0000🔐").forEachIndexed { index, value ->
      assertEquals(value, loaded.getMethod("text$index").invoke(null))
    }
  }

  @Test fun `custom codec uses its effective key and decryptor in classes and interfaces`() {
    for (inInterface in listOf(false, true)) {
      var generated = 0
      val codec = object : StringEncryptionCodec {
        override fun encrypt(value: String, key: Int) = StringEncryptionResult("xx$value", 2)
        override fun generateDecryptor(owner: String, method: MethodNode) {
          assertEquals("app/Strings", owner)
          generated++
          method.instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
          method.instructions.add(VarInsnNode(Opcodes.ILOAD, 1))
          method.instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(I)Ljava/lang/String;", false))
          method.instructions.add(InsnNode(Opcodes.ARETURN))
          method.maxStack = 2
          method.maxLocals = 2
        }
      }
      val passes = transformers {
        string {
          this.codec = codec
          includeConstantFields = false
        }
      }
      val node = owner(inInterface)
      passes.single().transform(ObfuscationContext(linkedMapOf(node.name to node), linkedMapOf()))
      assertEquals(1, generated)
      assertEquals(4, node.methods.size)
      assertRuntime(node)
    }
  }

  @Test fun `legacy XOR remains executable`() {
    val node = owner()
    StringEncryptionTransformer(Random(1)).transform(ObfuscationContext(linkedMapOf(node.name to node), linkedMapOf()))
    assertRuntime(node)
  }

  @Test fun `invalid decryptor declarations are rejected`() {
    for (changeSignature in listOf(false, true)) {
      val codec = object : StringEncryptionCodec {
        override fun encrypt(value: String, key: Int) = StringEncryptionResult(value, key)
        override fun generateDecryptor(owner: String, method: MethodNode) {
          if (changeSignature) method.desc = "()V"
        }
      }
      val node = owner()
      assertThrows(IllegalArgumentException::class.java) {
        StringEncryptionTransformer(Random(1), codec).transform(ObfuscationContext(linkedMapOf(node.name to node), linkedMapOf()))
      }
    }
  }

  @Test fun `unused codec does not generate helpers`() {
    val codec = object : StringEncryptionCodec {
      override fun encrypt(value: String, key: Int): StringEncryptionResult = error("unused")
      override fun generateDecryptor(owner: String, method: MethodNode) = error("unused")
    }
    val node = owner().apply { methods.clear() }
    StringEncryptionTransformer(Random(1), codec).transform(ObfuscationContext(linkedMapOf(node.name to node), linkedMapOf()))
    assertTrue(node.methods.isEmpty())
  }
}