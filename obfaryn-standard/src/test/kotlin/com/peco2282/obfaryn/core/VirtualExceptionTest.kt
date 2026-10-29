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
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import javax.tools.ToolProvider
import kotlin.io.path.writeText

class VirtualExceptionTest {
  @TempDir lateinit var dir: Path

  @Test fun typedOrderedNestedCatchesAndFinallyPreserveResultsStateAndExceptionIdentity() {
    val source = dir.resolve("ExceptionFixture.java")
    source.writeText("""
      public class ExceptionFixture {
        public static int hits;
        public static int guardedKotlin(Object value) {
          try { kotlin.jvm.internal.Intrinsics.checkNotNullParameter(value, "value"); return 1; }
          catch (NullPointerException e) { return 0; }
        }
        public static int divide(int v) { try { return 100 / v; } catch (ArithmeticException e) { return -7; } }
        public static int ordered(int v) {
          try { if (v == 0) throw new IllegalArgumentException("bad"); if (v == 1) throw new IllegalStateException("bad"); return v; }
          catch (IllegalArgumentException e) { return 11; } catch (RuntimeException e) { return 22; }
        }
        public static int nested(int v) {
          int local = 5;
          try { try { local = 9; return 100 / v; } finally { hits++; } }
          catch (ArithmeticException e) { return local; }
        }
        public static int finallyOverride(int v) { try { return 100 / v; } finally { hits++; if (v == 0) return 47; } }
        public static Object rethrow(RuntimeException e) { try { throw e; } catch (IllegalArgumentException caught) { return caught; } }
        public static int helper(int v) { return 10 / v; }
        public static int viaCall(int v) { try { return helper(v); } catch (ArithmeticException e) { return 31; } }
        public static int switchCatch(int v) {
          try { switch (v) { case 0: return 5 / v; case 1: throw new IllegalArgumentException(); default: return v; } }
          catch (ArithmeticException e) { return 17; } catch (IllegalArgumentException e) { return 19; }
        }
      }
    """.trimIndent())
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-classpath",
      java.io.File(kotlin.jvm.internal.Intrinsics::class.java.protectionDomain.codeSource.location.toURI()).path,
      "-d", dir.toString(), source.toString()))
    val bytes = Files.readAllBytes(dir.resolve("ExceptionFixture.class"))
    fun loader(definitions: Map<String, ByteArray>) = object : ClassLoader(javaClass.classLoader) {
      override fun findClass(name: String): Class<*> {
        val data = definitions[name.replace('.', '/')] ?: return super.findClass(name)
        return defineClass(name, data, 0, data.size)
      }
    }
    for (seed in listOf(0L, 1L, 17L)) {
      val original = loader(mapOf("ExceptionFixture" to bytes)).loadClass("ExceptionFixture")
      val node = ClassNode().apply { ClassReader(bytes).accept(this, 0) }
      val pattern = listOf("ExceptionFixture\\.(divide|ordered|nested|finallyOverride|rethrow|helper|viaCall|switchCatch|guardedKotlin).*" )
      val analysis = VirtualizationAnalyzer(pattern).analyze(listOf(node))
      assertEquals(9, analysis.eligible, analysis.toJson())
      val context = ObfuscationContext(linkedMapOf(node.name to node), linkedMapOf())
      VirtualizationTransformer(pattern, Random(seed)).transform(context)
      val definitions = context.classes.mapValues { (_, type) ->
        ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS).also { type.accept(it) }.toByteArray()
      }
      val transformed = loader(definitions).loadClass("ExceptionFixture")
      val integer = Int::class.javaPrimitiveType!!
      for (name in listOf("divide", "ordered", "nested", "finallyOverride", "viaCall", "switchCatch")) {
        for (value in listOf(-3, 0, 1, 2, 17)) {
          assertEquals(original.getMethod(name, integer).invoke(null, value),
            transformed.getMethod(name, integer).invoke(null, value), "$name($value), seed=$seed")
        }
      }
      assertEquals(original.getField("hits").getInt(null), transformed.getField("hits").getInt(null))
      assertEquals(0, transformed.getMethod("guardedKotlin", Any::class.java).invoke(null, null))
      assertEquals(1, transformed.getMethod("guardedKotlin", Any::class.java).invoke(null, Any()))
      val caught = IllegalArgumentException("identity")
      assertSame(caught, transformed.getMethod("rethrow", RuntimeException::class.java).invoke(null, caught))
      val escaping = IllegalStateException("identity")
      val thrown = assertThrows(InvocationTargetException::class.java) {
        transformed.getMethod("rethrow", RuntimeException::class.java).invoke(null, escaping)
      }
      assertSame(escaping, thrown.cause)
      assertEquals(-7, transformed.getMethod("divide", integer).invoke(null, 0))
    }
  }
}
