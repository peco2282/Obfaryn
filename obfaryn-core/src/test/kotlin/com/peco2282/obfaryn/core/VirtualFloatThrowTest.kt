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
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.writeText

class VirtualFloatThrowTest {
  @TempDir lateinit var dir: Path

  private fun fixture(): ObfuscationContext {
    val source = dir.resolve("FloatSample.java")
    source.writeText("""
      public class FloatSample {
        public float field = -0.0f;
        public static float global = 1.25f;
        public static float arithmetic(float a, float b) { return -((a + b) * (a - b) / b) + a % b; }
        public static int compare(float a, float b) { return a < b ? -1 : a > b ? 1 : a == b ? 0 : 2; }
        public static float fromInt(int v) { return (float)v; }
        public static int toInt(float v) { return (int)v; }
        public static float fromLong(long v) { return (float)v; }
        public static long toLong(float v) { return (long)v; }
        public static float fromDouble(double v) { return (float)v; }
        public static double toDouble(float v) { return (double)v; }
        public float fields(float v) { field = v; global = field; return global; }
        public static float invoke(float v) { return Math.abs(v); }
        public static float arrays(float v) { float[] values = new float[2]; values[1] = v; return values[1]; }
        public static int throwing(boolean fail) { if (fail) throw new IllegalArgumentException("boom"); return 7; }
        public static Outer nested(int value) { return new Outer(new Inner(value), "nested"); }
      }
      class Inner { final int value; Inner(int value) { this.value = value; } }
      class Outer {
        final Inner inner; final String name;
        Outer(Inner inner, String name) { this.inner = inner; this.name = name; }
        public String toString() { return inner.value + ":" + name; }
      }
    """.trimIndent())
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
      "--release", "17", "-d", dir.toString(), source.toString()))
    val nodes = linkedMapOf<String, ClassNode>()
    for (name in listOf("FloatSample", "Inner", "Outer")) {
      val node = ClassNode(); ClassReader(Files.readAllBytes(dir.resolve("$name.class"))).accept(node, 0); nodes[name] = node
    }
    return ObfuscationContext(nodes, linkedMapOf())
  }

  private fun jar(context: ObfuscationContext): Path {
    val path = dir.resolve("float.jar")
    JarOutputStream(Files.newOutputStream(path)).use { out ->
      for (node in context.classes.values) {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS); node.accept(writer)
        out.putNextEntry(JarEntry("${node.name}.class")); out.write(writer.toByteArray()); out.closeEntry()
      }
    }
    return path
  }

  @Test fun floatThrowAndNestedConstructorsExecute() {
    val selected = "arithmetic|compare|fromInt|toInt|fromLong|toLong|fromDouble|toDouble|fields|invoke|arrays|throwing|nested"
    val pattern = "FloatSample\\.($selected)\\(.*"
    val context = fixture()
    val report = VirtualizationAnalyzer(listOf(pattern)).analyze(context.classes.values)
    assertEquals(13, report.eligible, report.methods.filter { it.selected }.flatMap { it.reasons }.joinToString())
    val transformer = VirtualizationTransformer(listOf(pattern), Random(123), 3)
    transformer.transform(context)
    assertEquals(13, transformer.count)
    URLClassLoader(arrayOf(dir.toUri().toURL()), platformClassLoader()).use { original ->
      URLClassLoader(arrayOf(jar(context).toUri().toURL()), platformClassLoader()).use { transformed ->
        val before = original.loadClass("FloatSample"); val after = transformed.loadClass("FloatSample")
        val f = Float::class.javaPrimitiveType!!; val i = Int::class.javaPrimitiveType!!
        val j = Long::class.javaPrimitiveType!!; val d = Double::class.javaPrimitiveType!!
        val z = Boolean::class.javaPrimitiveType!!
        val values = listOf(-0.0f, 0.0f, -3.5f, 2.25f, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NaN)
        fun raw(value: Any?) = java.lang.Float.floatToRawIntBits(value as Float)
        for (a in values) for (b in listOf(-2f, 0f, 3f, Float.NaN)) {
          assertEquals(raw(before.getMethod("arithmetic", f, f).invoke(null, a, b)),
            raw(after.getMethod("arithmetic", f, f).invoke(null, a, b)))
          assertEquals(before.getMethod("compare", f, f).invoke(null, a, b), after.getMethod("compare", f, f).invoke(null, a, b))
        }
        for (v in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE))
          assertEquals(raw(before.getMethod("fromInt", i).invoke(null, v)), raw(after.getMethod("fromInt", i).invoke(null, v)))
        for (v in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE))
          assertEquals(raw(before.getMethod("fromLong", j).invoke(null, v)), raw(after.getMethod("fromLong", j).invoke(null, v)))
        for (v in values) {
          assertEquals(before.getMethod("toInt", f).invoke(null, v), after.getMethod("toInt", f).invoke(null, v))
          assertEquals(before.getMethod("toLong", f).invoke(null, v), after.getMethod("toLong", f).invoke(null, v))
          assertEquals(before.getMethod("toDouble", f).invoke(null, v), after.getMethod("toDouble", f).invoke(null, v))
          assertEquals(raw(before.getMethod("invoke", f).invoke(null, v)), raw(after.getMethod("invoke", f).invoke(null, v)))
          assertEquals(raw(before.getMethod("arrays", f).invoke(null, v)), raw(after.getMethod("arrays", f).invoke(null, v)))
        }
        for (v in listOf(Double.NaN, Double.NEGATIVE_INFINITY, -0.0, 2.5, Double.POSITIVE_INFINITY))
          assertEquals(raw(before.getMethod("fromDouble", d).invoke(null, v)), raw(after.getMethod("fromDouble", d).invoke(null, v)))
        assertEquals(7, after.getMethod("throwing", z).invoke(null, false))
        val thrown = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
          after.getMethod("throwing", z).invoke(null, true)
        }
        assertInstanceOf(IllegalArgumentException::class.java, thrown.cause)
        assertEquals("boom", thrown.cause!!.message)
        assertEquals("9:nested", after.getMethod("nested", i).invoke(null, 9).toString())
      }
    }
  }
}
