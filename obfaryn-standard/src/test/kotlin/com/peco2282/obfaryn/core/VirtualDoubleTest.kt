package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.transformer.VirtualizationAnalyzer
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

class VirtualDoubleTest {
  @TempDir lateinit var dir: Path

  private fun fixture(): ObfuscationContext {
    val source = dir.resolve("DoubleSample.java")
    source.writeText("""
      public class DoubleSample {
        public double value = -0.0d;
        public static double global = 1.25d;
        public static double arithmetic(double a, double b) {
          double sum = a + b;
          double difference = a - b;
          return -(sum * difference) / b + a % b;
        }
        public static int compare(double a, double b) {
          if (a < b) return -1;
          if (a > b) return 1;
          if (a == b) return 0;
          return 2;
        }
        public static double fromInt(int value) { return (double)value; }
        public static int toInt(double value) { return (int)value; }
        public double fields(double next) { value = next; global = value; return global; }
        public static double invoke(double value) { return Math.abs(value); }
        public static double constants(boolean one) { return one ? 1.0d : -0.0d; }
      }
    """.trimIndent())
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
      "--release", "17", "-d", dir.toString(), source.toString()))
    val node = ClassNode()
    ClassReader(Files.readAllBytes(dir.resolve("DoubleSample.class"))).accept(node, 0)
    return ObfuscationContext(linkedMapOf(node.name to node), linkedMapOf())
  }

  private fun jar(context: ObfuscationContext): Path {
    val path = dir.resolve("double-virtual.jar")
    JarOutputStream(Files.newOutputStream(path)).use { out ->
      for (node in context.classes.values) {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        out.putNextEntry(JarEntry("${node.name}.class")); out.write(writer.toByteArray()); out.closeEntry()
      }
    }
    return path
  }

  @Test fun doubleMethodsAreEligibleAndPreserveRuntimeBehavior() {
    val context = fixture()
    val selected = "arithmetic|compare|fromInt|toInt|fields|invoke|constants"
    val report = VirtualizationAnalyzer(listOf("DoubleSample\\.($selected)\\(.*")).analyze(context.classes.values)
    assertEquals(7, report.eligible, report.methods.filter { it.selected }.flatMap { it.reasons }.joinToString())
    val transformer = VirtualizationTransformer(listOf("DoubleSample\\.($selected)\\(.*"), Random(42), 2)
    transformer.transform(context)
    assertEquals(7, transformer.count)
    assertTrue(context.classes.getValue("DoubleSample").methods.filter { it.name in selected.split('|') }
      .flatMap { it.instructions.toArray().filterIsInstance<MethodInsnNode>() }
      .any { it.name == "executeDouble" && it.desc == "(Ljava/lang/String;[I[Ljava/lang/Object;[J)D" })

    URLClassLoader(arrayOf(dir.toUri().toURL()), platformClassLoader()).use { original ->
      URLClassLoader(arrayOf(jar(context).toUri().toURL()), platformClassLoader()).use { transformed ->
        val before = original.loadClass("DoubleSample")
        val after = transformed.loadClass("DoubleSample")
        val doubleType = Double::class.javaPrimitiveType!!
        val intType = Int::class.javaPrimitiveType!!
        val booleanType = Boolean::class.javaPrimitiveType!!
        val values = listOf(-0.0, 0.0, -3.5, 2.25, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN)
        fun raw(value: Any?) = java.lang.Double.doubleToRawLongBits(value as Double)
        for (a in values) for (b in listOf(-2.0, 0.0, 3.0, Double.NaN)) {
          assertEquals(raw(before.getMethod("arithmetic", doubleType, doubleType).invoke(null, a, b)),
            raw(after.getMethod("arithmetic", doubleType, doubleType).invoke(null, a, b)), "arithmetic($a,$b)")
          assertEquals(before.getMethod("compare", doubleType, doubleType).invoke(null, a, b),
            after.getMethod("compare", doubleType, doubleType).invoke(null, a, b), "compare($a,$b)")
        }
        for (value in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE))
          assertEquals(raw(before.getMethod("fromInt", intType).invoke(null, value)),
            raw(after.getMethod("fromInt", intType).invoke(null, value)))
        for (value in values) assertEquals(before.getMethod("toInt", doubleType).invoke(null, value),
          after.getMethod("toInt", doubleType).invoke(null, value))
        val a = before.getConstructor().newInstance()
        val b = after.getConstructor().newInstance()
        for (value in values) assertEquals(raw(before.getMethod("fields", doubleType).invoke(a, value)),
          raw(after.getMethod("fields", doubleType).invoke(b, value)))
        for (value in values) assertEquals(raw(before.getMethod("invoke", doubleType).invoke(null, value)),
          raw(after.getMethod("invoke", doubleType).invoke(null, value)))
        for (value in listOf(false, true)) assertEquals(raw(before.getMethod("constants", booleanType).invoke(null, value)),
          raw(after.getMethod("constants", booleanType).invoke(null, value)))
      }
    }
  }
}
