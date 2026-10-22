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

  private fun loadOutput(node: ClassNode): Class<*> {
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    node.accept(writer)
    val bytes = writer.toByteArray()
    return object : ClassLoader(javaClass.classLoader) {
      fun loadOutput() = defineClass(null, bytes, 0, bytes.size)
    }.loadOutput()
  }

  private fun assertRuntime(node: ClassNode): Class<*> {
    val loaded = loadOutput(node)
    listOf("", "hello", "日本語\u0000🔐").forEachIndexed { index, value ->
      val method = loaded.getMethod("text$index")
      assertEquals(value, method.invoke(null))
      assertSame(value.intern(), method.invoke(null))
      assertSame(method.invoke(null), method.invoke(null))
    }
    return loaded
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
      assertRuntime(node)
    }
  }

  @Test fun `default XOR codec is executable in classes and interfaces`() {
    for (inInterface in listOf(false, true)) {
      val node = owner(inInterface)
      StringEncryptionTransformer(Random(1)).transform(ObfuscationContext(linkedMapOf(node.name to node), linkedMapOf()))
      assertRuntime(node)
    }
  }

  companion object {
    private val decoded = java.util.concurrent.atomic.AtomicInteger()
    @JvmStatic fun countedDecode(value: String, key: Int): String {
      decoded.incrementAndGet()
      return String(value.substring(key).toCharArray())
    }
  }

  @Test fun `cache avoids repeated decoding and preserves identity under concurrent calls`() {
    val codec = object : StringEncryptionCodec {
      override fun encrypt(value: String, key: Int) = StringEncryptionResult("xx$value", 2)
      override fun generateDecryptor(owner: String, method: MethodNode) {
        method.instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
        method.instructions.add(VarInsnNode(Opcodes.ILOAD, 1))
        method.instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC,
          "com/peco2282/obfaryn/core/StringEncryptionCodecTest", "countedDecode",
          "(Ljava/lang/String;I)Ljava/lang/String;", false))
        method.instructions.add(InsnNode(Opcodes.ARETURN))
        method.maxLocals = 2
        method.maxStack = 2
      }
    }
    for (inInterface in listOf(false, true)) {
      decoded.set(0)
      val node = owner(inInterface)
      // Read an encrypted literal while the owning class is still being initialized.
      node.fields.add(FieldNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
        "initial", "Ljava/lang/String;", null, null))
      node.methods.add(MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
        instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, node.name, "text1", "()Ljava/lang/String;", inInterface))
        instructions.add(FieldInsnNode(Opcodes.PUTSTATIC, node.name, "initial", "Ljava/lang/String;"))
        instructions.add(InsnNode(Opcodes.RETURN))
        maxStack = 1
      })
      StringEncryptionTransformer(Random(1), codec).transform(ObfuscationContext(linkedMapOf(node.name to node), linkedMapOf()))
      val loaded = assertRuntime(node)
      assertSame("hello", loaded.getField("initial").get(null))
      assertEquals(3, decoded.get())
      val method = loaded.getMethod("text1")
      val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
      try {
        val results = pool.invokeAll((0 until 100).map {
          java.util.concurrent.Callable { method.invoke(null) }
        })
        results.forEach { assertSame("hello", it.get()) }
      } finally { pool.shutdownNow() }
      assertEquals(3, decoded.get())
    }
  }

  @Test fun `concurrent first reads publish the same interned literal`() {
    for (inInterface in listOf(false, true)) {
      val node = owner(inInterface)
      StringEncryptionTransformer(Random(8)).transform(ObfuscationContext(linkedMapOf(node.name to node), linkedMapOf()))
      val loaded = loadOutput(node)
      val method = loaded.getMethod("text2")
      val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
      val barrier = java.util.concurrent.CyclicBarrier(4)
      try {
        val tasks = (0 until 4).map {
          java.util.concurrent.Callable {
            barrier.await(10, java.util.concurrent.TimeUnit.SECONDS)
            method.invoke(null)
          }
        }
        val results = pool.invokeAll(tasks, 15, java.util.concurrent.TimeUnit.SECONDS)
        results.forEach { assertSame("日本語\u0000🔐", it.get()) }
        assertSame(results.first().get(), method.invoke(null))
      } finally { pool.shutdownNow() }
    }
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