package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.context.ObfuscationContext
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
import java.util.concurrent.Executors
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.writeText

class VirtualReferenceTest {
  @TempDir lateinit var dir: Path

  private fun fixture(): ObfuscationContext {
    val source = dir.resolve("ReferenceSample.java")
    source.writeText("""
      public class ReferenceSample {
        public int calculate(int value) { return value * 3; }
        public Object identity(Object value) { Object local = value; return local; }
        public Object self() { return this; }
        public Object nothing() { return null; }
        public int same(Object a, Object b) { return a == b ? 1 : 0; }
        public int isNull(Object a) { return a == null ? 1 : 0; }
        public int slots(Object a, int b, Object c, int d) { return a == c ? b + d : b - d; }
        public void discard(Object value) { Object local = value; }
      }
    """.trimIndent())
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
      "--release", "17", "-d", dir.toString(), source.toString()))
    val node = ClassNode()
    ClassReader(Files.readAllBytes(dir.resolve("ReferenceSample.class"))).accept(node, 0)
    return ObfuscationContext(linkedMapOf(node.name to node), linkedMapOf())
  }

  private fun jar(context: ObfuscationContext): Path {
    val jar = dir.resolve("reference.jar")
    JarOutputStream(Files.newOutputStream(jar)).use { out ->
      for (node in context.classes.values) {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        out.putNextEntry(JarEntry("${node.name}.class")); out.write(writer.toByteArray()); out.closeEntry()
      }
    }
    return jar
  }

  @Test fun instanceAndReferenceProgramsExecuteConcurrently() {
    for (seed in listOf(1L, 42L, 43L)) {
      val context = fixture()
      VirtualizationTransformer(listOf("ReferenceSample\\.(?!<).*"), Random(seed)).transform(context)
      URLClassLoader(arrayOf(dir.toUri().toURL()), platformClassLoader()).use { original ->
        URLClassLoader(arrayOf(jar(context).toUri().toURL()), platformClassLoader()).use { transformed ->
          val before = original.loadClass("ReferenceSample")
          val after = transformed.loadClass("ReferenceSample")
          val a = before.getConstructor().newInstance()
          val b = after.getConstructor().newInstance()
          val value = Any()
          val other = Any()
          val integer = Int::class.javaPrimitiveType!!
          for (n in listOf(-100, 0, 1, Int.MAX_VALUE)) {
            assertEquals(before.getMethod("calculate", integer).invoke(a, n), after.getMethod("calculate", integer).invoke(b, n))
          }
          assertSame(b, after.getMethod("self").invoke(b))
          assertSame(value, after.getMethod("identity", Any::class.java).invoke(b, value))
          assertNull(after.getMethod("nothing").invoke(b))
          for (x in listOf(null, value, other)) for (y in listOf(null, value, other)) {
            assertEquals(before.getMethod("same", Any::class.java, Any::class.java).invoke(a, x, y),
              after.getMethod("same", Any::class.java, Any::class.java).invoke(b, x, y))
            assertEquals(before.getMethod("slots", Any::class.java, integer, Any::class.java, integer).invoke(a, x, 7, y, 3),
              after.getMethod("slots", Any::class.java, integer, Any::class.java, integer).invoke(b, x, 7, y, 3))
            assertEquals(before.getMethod("isNull", Any::class.java).invoke(a, x), after.getMethod("isNull", Any::class.java).invoke(b, x))
          }
          assertNull(after.getMethod("discard", Any::class.java).invoke(b, value))
          val executor = Executors.newFixedThreadPool(4)
          try {
            val results = (0 until 100).map { n -> executor.submit<Int> { after.getMethod("calculate", integer).invoke(b, n) as Int } }
            results.forEachIndexed { n, result -> assertEquals(n * 3, result.get()) }
          } finally { executor.shutdownNow() }
        }
      }
    }
  }
}