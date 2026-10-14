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

class VirtualWideArrayConstructorTest {
  @TempDir lateinit var dir: Path

  private fun fixture(): ObfuscationContext {
    val source = dir.resolve("WideSample.java")
    source.writeText("""
      public class WideSample {
        public long field = 7L;
        public static long global = 11L;
        public static long arithmetic(long a, long b, int shift) {
          long x = (a + b) * (a - b);
          return -x / b + a % b + (a << shift) + (a >> shift) + (a >>> shift) + (a & b) + (a | b) + (a ^ b);
        }
        public static int compare(long a, long b) { return a < b ? -1 : a == b ? 0 : 1; }
        public static long fromInt(int value) { return (long)value; }
        public static int toInt(long value) { return (int)value; }
        public static double toDouble(long value) { return (double)value; }
        public static long fromDouble(double value) { return (long)value; }
        public long fields(long value) { field = value; global = field; return global; }
        public static long invoke(long value, int distance) { return Long.rotateLeft(value, distance); }
        public static int ints(int a, int b) { int[] v = new int[3]; v[0] = a; v[1] = b; return v.length + v[0] + v[1]; }
        public static long longs(long a, long b) { long[] v = new long[2]; v[0] = a; v[1] = b; return v[0] ^ v[1]; }
        public static double doubles(double a) { double[] v = new double[1]; v[0] = a; return v[0]; }
        public static Object references(Object a) { Object[] v = new Object[1]; v[0] = a; return v[0]; }
        public static int bytes(int a) { byte[] v = new byte[1]; v[0] = (byte)a; return v[0]; }
        public static int booleans(boolean a) { boolean[] v = new boolean[1]; v[0] = a; return v[0] ? 1 : 0; }
        public static int chars(int a) { char[] v = new char[1]; v[0] = (char)a; return v[0]; }
        public static int shorts(int a) { short[] v = new short[1]; v[0] = (short)a; return v[0]; }
        public static Box construct(int a, long b, String c) { return new Box(a, b, c); }
        public static String builder(String value) { return new StringBuilder(value).append(':').append(7).toString(); }
      }
      class Box {
        final int a; final long b; final String c;
        Box(int a, long b, String c) { this.a = a; this.b = b; this.c = c; }
        public String toString() { return a + ":" + b + ":" + c; }
      }
    """.trimIndent())
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
      "--release", "17", "-d", dir.toString(), source.toString()))
    val nodes = linkedMapOf<String, ClassNode>()
    for (name in listOf("WideSample", "Box")) {
      val node = ClassNode(); ClassReader(Files.readAllBytes(dir.resolve("$name.class"))).accept(node, 0); nodes[name] = node
    }
    return ObfuscationContext(nodes, linkedMapOf())
  }

  private fun jar(context: ObfuscationContext): Path {
    val path = dir.resolve("wide.jar")
    JarOutputStream(Files.newOutputStream(path)).use { out ->
      for (node in context.classes.values) {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS); node.accept(writer)
        out.putNextEntry(JarEntry("${node.name}.class")); out.write(writer.toByteArray()); out.closeEntry()
      }
    }
    return path
  }

  @Test fun longArraysAndCanonicalConstructorsExecute() {
    val context = fixture()
    val selected = "arithmetic|compare|fromInt|toInt|toDouble|fromDouble|fields|invoke|ints|longs|doubles|references|bytes|booleans|chars|shorts|construct|builder"
    val pattern = "WideSample\\.($selected)\\(.*"
    val report = VirtualizationAnalyzer(listOf(pattern)).analyze(context.classes.values)
    assertEquals(18, report.eligible, report.methods.filter { it.selected }.flatMap { it.reasons }.joinToString())
    val transformer = VirtualizationTransformer(listOf(pattern), Random(91), 3)
    transformer.transform(context)
    assertEquals(18, transformer.count)
    URLClassLoader(arrayOf(dir.toUri().toURL()), platformClassLoader()).use { original ->
      URLClassLoader(arrayOf(jar(context).toUri().toURL()), platformClassLoader()).use { transformed ->
        val before = original.loadClass("WideSample"); val after = transformed.loadClass("WideSample")
        val j = Long::class.javaPrimitiveType!!; val i = Int::class.javaPrimitiveType!!
        val d = Double::class.javaPrimitiveType!!; val z = Boolean::class.javaPrimitiveType!!
        for (a in listOf(Long.MIN_VALUE, -17L, 0L, 19L, Long.MAX_VALUE)) for (b in listOf(-3L, 1L, 7L)) {
          assertEquals(before.getMethod("arithmetic", j, j, i).invoke(null, a, b, 5),
            after.getMethod("arithmetic", j, j, i).invoke(null, a, b, 5))
          assertEquals(before.getMethod("compare", j, j).invoke(null, a, b), after.getMethod("compare", j, j).invoke(null, a, b))
        }
        for (value in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) {
          assertEquals(before.getMethod("fromInt", i).invoke(null, value), after.getMethod("fromInt", i).invoke(null, value))
          assertEquals(before.getMethod("bytes", i).invoke(null, value), after.getMethod("bytes", i).invoke(null, value))
          assertEquals(before.getMethod("chars", i).invoke(null, value), after.getMethod("chars", i).invoke(null, value))
          assertEquals(before.getMethod("shorts", i).invoke(null, value), after.getMethod("shorts", i).invoke(null, value))
        }
        for (value in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) {
          assertEquals(before.getMethod("toInt", j).invoke(null, value), after.getMethod("toInt", j).invoke(null, value))
          assertEquals(before.getMethod("toDouble", j).invoke(null, value), after.getMethod("toDouble", j).invoke(null, value))
          assertEquals(before.getMethod("invoke", j, i).invoke(null, value, 13), after.getMethod("invoke", j, i).invoke(null, value, 13))
        }
        for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, -0.0, 2.5, Double.POSITIVE_INFINITY))
          assertEquals(before.getMethod("fromDouble", d).invoke(null, value), after.getMethod("fromDouble", d).invoke(null, value))
        val x = Any()
        assertSame(x, after.getMethod("references", Any::class.java).invoke(null, x))
        assertEquals(before.getMethod("ints", i, i).invoke(null, 3, 9), after.getMethod("ints", i, i).invoke(null, 3, 9))
        assertEquals(before.getMethod("longs", j, j).invoke(null, 3L, 9L), after.getMethod("longs", j, j).invoke(null, 3L, 9L))
        assertEquals(before.getMethod("doubles", d).invoke(null, -0.0), after.getMethod("doubles", d).invoke(null, -0.0))
        for (value in listOf(false, true)) assertEquals(before.getMethod("booleans", z).invoke(null, value),
          after.getMethod("booleans", z).invoke(null, value))
        assertEquals("4:8:value", after.getMethod("construct", i, j, String::class.java).invoke(null, 4, 8L, "value").toString())
        assertEquals("value:7", after.getMethod("builder", String::class.java).invoke(null, "value"))
        val first = before.getConstructor().newInstance(); val second = after.getConstructor().newInstance()
        assertEquals(before.getMethod("fields", j).invoke(first, 99L), after.getMethod("fields", j).invoke(second, 99L))
      }
    }
  }
}
