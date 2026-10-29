package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.transformer.VirtualizationTransformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.writeText

class VirtualizationTest {
  @TempDir lateinit var dir: Path

  private fun fixture(): ObfuscationContext {
    val source = dir.resolve("Calc.java")
    source.writeText("""
      public class Calc {
        public static int loop(int n) { int sum = 0; for (int i = 0; i < n; i++) sum += i * 3; return sum; }
        public static int math(int a, int b) { return ((a / b + a % b) ^ (a >>> b)) - (a << b) + (a & b) + (a | b) + (a >> b); }
        public static int narrow(int a) { return (byte)a + (short)a + (char)a; }
        public static int branch(int a) { if (a == 0) return 17; if (a < 0) return -a; return a - 1; }
        public static int untouched(int a) { return a + 41; }
        public static int bad(int a) { synchronized (Calc.class) { return a + 1; } }
        public static long wrong(long a) { return a; }
      }
    """.trimIndent())
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
      "--release", "17", "-d", dir.toString(), source.toString()))
    val node = ClassNode()
    ClassReader(Files.readAllBytes(dir.resolve("Calc.class"))).accept(node, 0)
    return ObfuscationContext(linkedMapOf(node.name to node), linkedMapOf())
  }

  @Test fun encodedProgramsExecuteWithoutKotlinRuntime() {
    for (seed in listOf(0L, 1L, 2L, 3L, 42L, 43L)) verifyExecution(seed)
  }

  private fun verifyExecution(seed: Long) {
    val context = fixture()
    val untouched = context.classes.getValue("Calc").methods.single { it.name == "untouched" }.instructions
    val transformer = VirtualizationTransformer(listOf("Calc\\.(loop|math|narrow|branch)\\(I+\\)I"), Random(seed))
    transformer.transform(context)
    assertEquals(5, transformer.order)
    assertEquals(4, transformer.count)
    assertSame(untouched, context.classes.getValue("Calc").methods.single { it.name == "untouched" }.instructions)
    assertEquals(2, context.classes.size)
    assertTrue(context.classes.values.filter { it.name != "Calc" }.all { it.version == org.objectweb.asm.Opcodes.V1_8 })
    assertFalse(context.classes.values.filter { it.name != "Calc" }.flatMap { it.methods }.flatMap { it.instructions.toArray().toList() }
      .any { it is org.objectweb.asm.tree.InvokeDynamicInsnNode })
    for (method in context.classes.getValue("Calc").methods.filter { it.name in setOf("loop", "math", "narrow", "branch") }) {
      assertTrue(method.instructions.toArray().filterIsInstance<MethodInsnNode>().any {
        it.name == "execute" && it.desc == "(Ljava/lang/String;[I)I"
      })
    }
    val jar = dir.resolve("virtual.jar")
    JarOutputStream(Files.newOutputStream(jar)).use { out ->
      for (node in context.classes.values) {
        val writer = ClassWriter(0)
        node.accept(writer)
        out.putNextEntry(JarEntry("${node.name}.class"))
        out.write(writer.toByteArray())
        out.closeEntry()
      }
    }
    URLClassLoader(arrayOf(dir.toUri().toURL()), platformClassLoader()).use { original ->
      URLClassLoader(arrayOf(jar.toUri().toURL()), platformClassLoader()).use { transformed ->
        val before = original.loadClass("Calc")
        val after = transformed.loadClass("Calc")
        for (name in listOf("loop", "branch", "narrow")) {
          for (value in listOf(-100, -1, 0, 1, 23, 65535)) {
            assertEquals(before.getMethod(name, Int::class.javaPrimitiveType).invoke(null, value),
              after.getMethod(name, Int::class.javaPrimitiveType).invoke(null, value), "$name($value)")
          }
        }
        for (a in listOf(Int.MIN_VALUE, -77, 0, 99, Int.MAX_VALUE)) for (b in listOf(-1, 1, 3, 32)) {
          assertEquals(before.getMethod("math", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(null, a, b),
            after.getMethod("math", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(null, a, b))
        }
        val error = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
          after.getMethod("math", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(null, 7, 0)
        }
        assertInstanceOf(ArithmeticException::class.java, error.cause)
      }
    }
  }

  @Test fun unsupportedSelectionFailsBeforeMutation() {
    val context = fixture()
    val loop = context.classes.getValue("Calc").methods.single { it.name == "loop" }.instructions
    val transformer = VirtualizationTransformer(listOf("Calc\\.(loop|bad)\\(I\\)I"), Random(1))
    val error = assertThrows(IllegalArgumentException::class.java) { transformer.transform(context) }
    assertTrue(error.message!!.contains("Calc.bad(I)I"))
    assertTrue(error.message!!.contains("opcode"))
    assertEquals(0, transformer.count)
    assertEquals(1, context.classes.size)
    assertSame(loop, context.classes.getValue("Calc").methods.single { it.name == "loop" }.instructions)
    val wide = VirtualizationTransformer(listOf("Calc\\.wrong\\(J\\)J"), Random(1))
    wide.transform(context)
    assertEquals(1, wide.count)
  }

  @Test fun selectorUsesFullMatch() {
    val context = fixture()
    val transformer = VirtualizationTransformer(listOf("loop", "Calc\\.loop"), Random(1))
    transformer.transform(context)
    assertEquals(0, transformer.count)
    assertEquals(1, context.classes.size)
  }

  @Test fun explicitExclusionOverridesSelection() {
    val context = fixture()
    val transformer = VirtualizationTransformer(
      listOf("Calc\\.(loop|math)\\(I+\\)I"), Random(1), exclusions = listOf("Calc\\.loop\\(I\\)I"))
    transformer.transform(context)
    assertEquals(1, transformer.count)
    assertTrue(context.classes.getValue("Calc").methods.single { it.name == "loop" }.instructions.toArray()
      .none { it is MethodInsnNode && it.name == "execute" })
    assertTrue(context.classes.getValue("Calc").methods.single { it.name == "math" }.instructions.toArray()
      .any { it is MethodInsnNode && it.name == "execute" })
  }

  @Test fun groupSizeControlsIndependentRuntimes() {
    for (size in listOf(1, 2, 3, 10)) {
      val context = fixture()
      VirtualizationTransformer(listOf("Calc\\.(loop|math|narrow|branch)\\(I+\\)I"), Random(42), size).transform(context)
      val calls = context.classes.getValue("Calc").methods.filter { it.name in setOf("loop", "math", "narrow", "branch") }
        .flatMap { it.instructions.toArray().filterIsInstance<MethodInsnNode>() }.filter { it.name == "execute" }
      assertEquals((4 + size - 1) / size, calls.map { it.owner }.distinct().size)
      assertTrue(calls.all { it.desc == "(Ljava/lang/String;[I)I" })
      assertTrue(context.classes.values.filter { it.name != "Calc" }.all { node ->
        node.methods.none { it.name == "configuration" }
      })
    }
    assertThrows(IllegalArgumentException::class.java) { VirtualizationTransformer(emptyList(), Random(1), 0) }
  }

  @Test fun seededProgramsAreReproducibleAndStructurallyDiverse() {
    fun output(seed: Long): List<List<Byte>> {
      val context = fixture()
      VirtualizationTransformer(listOf("Calc\\.loop\\(I\\)I"), Random(seed)).transform(context)
      return context.classes.values.map { node ->
        val writer = ClassWriter(0)
        node.accept(writer)
        writer.toByteArray().toList()
      }
    }
    assertEquals(output(42), output(42))
    assertNotEquals(output(42), output(43))
    val layouts = (0L..30L).map {
      val layout = com.peco2282.obfaryn.core.transformer.VirtualLayout(Random(it))
      listOf(layout.width, layout.operationSlot, layout.operandSlot, layout.extraSlot)
    }.toSet()
    assertTrue(layouts.size > 1)
  }
}
