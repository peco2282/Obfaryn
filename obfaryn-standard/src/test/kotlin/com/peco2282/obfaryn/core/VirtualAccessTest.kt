package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.transformer.VirtualizationTransformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import javax.tools.ToolProvider
import kotlin.io.path.writeText

class VirtualAccessTest {
  @TempDir lateinit var dir: Path

  @Test fun protectedAccessAcrossPackagesExecutesFromOriginalCaller() {
    val base = dir.resolve("Base.java")
    val child = dir.resolve("Child.java")
    base.writeText("""
      package parent;
      public class Base {
        protected int value = 7;
        protected int helper(int n) { return n + value; }
      }
    """.trimIndent())
    child.writeText("""
      package application;
      public class Child extends parent.Base {
        public int calculate(int n) { value = n; return helper(n) + value; }
      }
    """.trimIndent())
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
      "--release", "17", "-d", dir.toString(), base.toString(), child.toString()))
    val nodes = listOf("parent/Base", "application/Child").associateWith { name ->
      ClassNode().apply { ClassReader(Files.readAllBytes(dir.resolve("$name.class"))).accept(this, 0) }
    }.toMutableMap()
    val context = ObfuscationContext(nodes, linkedMapOf())
    VirtualizationTransformer(listOf("application/Child\\.calculate\\(I\\)I"), Random(42)).transform(context)
    val definitions = context.classes.mapKeys { it.key.replace('/', '.') }.mapValues { (_, node) ->
      ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS).also(node::accept).toByteArray()
    }
    val loader = object : ClassLoader(platformClassLoader()) {
      override fun findClass(name: String): Class<*> = definitions[name]?.let { defineClass(name, it, 0, it.size) }
        ?: super.findClass(name)
    }
    val type = loader.loadClass("application.Child")
    val instance = type.getConstructor().newInstance()
    for (n in listOf(-7, 0, 12)) assertEquals(n * 3, type.getMethod("calculate", Int::class.javaPrimitiveType).invoke(instance, n))
  }

  @Test fun unresolvedBridgeAccessFailsBeforeAnyMutation() {
    val method = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "call", "(Lmissing/Dependency;)I", null, null).apply {
      instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
      instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "missing/Dependency", "value", "()I", false))
      instructions.add(InsnNode(Opcodes.IRETURN))
      maxLocals = 1; maxStack = 1
    }
    val owner = ClassNode().apply { version = Opcodes.V17; name = "Caller"; superName = "java/lang/Object"; methods.add(method) }
    val context = ObfuscationContext(linkedMapOf(owner.name to owner), linkedMapOf())
    val body = method.instructions
    val before = ClassWriter(0).also(owner::accept).toByteArray()
    val transformer = VirtualizationTransformer(listOf("Caller\\..*"), Random(42))
    val error = assertThrows(IllegalArgumentException::class.java) { transformer.transform(context) }
    assertTrue(error.message!!.contains("classpath"))
    assertEquals(0, transformer.count)
    assertEquals(listOf("Caller"), context.classes.keys.toList())
    assertEquals(listOf(method), owner.methods)
    assertSame(body, method.instructions)
    assertArrayEquals(before, ClassWriter(0).also(owner::accept).toByteArray())
  }
}