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
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import javax.tools.ToolProvider
import kotlin.io.path.writeText

class VirtualSwitchConstantTest {
  @TempDir lateinit var dir: Path

  @Test fun denseSparseSwitchesAndClassLiteralsPreserveJvmBehavior() {
    val source = dir.resolve("SwitchFixture.java")
    source.writeText( """
      public class SwitchFixture {
        public static int dense(int v) {
          return 11 + switch (v) { case -1: yield 4; case 0: yield 7; case 1: yield 9; default: yield 13; };
        }
        public static int sparse(int v) {
          switch (v) { case Integer.MIN_VALUE: return 3; case 2000: return 5; case Integer.MAX_VALUE: return 7; default: return -1; }
        }
        public static Class<?> arrays() { return String[][].class; }
        public static Class<?> ownType() { return SwitchFixture.class; }
      }
    """.trimIndent())
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dir.toString(), source.toString()))
    val original = Files.readAllBytes(dir.resolve("SwitchFixture.class"))
    for (seed in listOf(1L, 17L)) {
      val node = ClassNode().apply { ClassReader(original).accept(this, 0) }
      val patterns = listOf("SwitchFixture\\.(dense|sparse|arrays|ownType).*" )
      val analysis = VirtualizationAnalyzer(patterns).analyze(listOf(node))
      assertEquals(4, analysis.eligible, analysis.toJson())
      val context = ObfuscationContext(linkedMapOf(node.name to node), linkedMapOf())
      val transformer = VirtualizationTransformer(patterns, Random(seed))
      transformer.transform(context)
      assertEquals(4, transformer.count)
      val definitions = context.classes.mapValues { (_, type) ->
        ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS).also { type.accept(it) }.toByteArray()
      }
      val loader = object : ClassLoader(javaClass.classLoader) {
        override fun findClass(name: String): Class<*> {
          val data = definitions[name.replace('.', '/')] ?: return super.findClass(name)
          return defineClass(name, data, 0, data.size)
        }
      }
      val type = loader.loadClass("SwitchFixture")
      val integer = Int::class.javaPrimitiveType!!
      for (value in listOf(Int.MIN_VALUE, -2, -1, 0, 1, 2, 2000, Int.MAX_VALUE)) {
        assertEquals(11 + when (value) { -1 -> 4; 0 -> 7; 1 -> 9; else -> 13 }, type.getMethod("dense", integer).invoke(null, value))
        assertEquals(when (value) { Int.MIN_VALUE -> 3; 2000 -> 5; Int.MAX_VALUE -> 7; else -> -1 }, type.getMethod("sparse", integer).invoke(null, value))
      }
      assertSame(Array<Array<String>>::class.java, type.getMethod("arrays").invoke(null))
      assertSame(type, type.getMethod("ownType").invoke(null))
    }
  }
}
