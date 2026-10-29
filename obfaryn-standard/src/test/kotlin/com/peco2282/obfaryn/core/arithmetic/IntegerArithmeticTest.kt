package com.peco2282.obfaryn.core.arithmetic

import com.peco2282.obfaryn.core.*
import com.peco2282.obfaryn.core.fixtures.JavaJarFixture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Random

class IntegerArithmeticTest {
  @TempDir lateinit var dir: Path

  @Test fun modularOverflowWideValuesAndOperandSideEffectsMatchOriginal() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Arithmetic" to """
      package app; public class Arithmetic {
        public static int trace;
        private static int next(int n) { trace = trace * 10 + n; return n; }
        public static int sideEffects() { trace = 0; return next(1) + next(2) - next(3); }
        public static int ints(int a, int b) { return ((a + b) ^ (a - b)) + (a ^ b); }
        public static long longs(long a, long b) { return ((a + b) ^ (a - b)) + (a ^ b); }
        public static double floating(double a, double b) { return a + b; }
      }
    """.trimIndent()))
    URLClassLoader(arrayOf(input.toUri().toURL()), null).use { originalLoader ->
      val original = originalLoader.loadClass("app.Arithmetic")
      for (seed in 0L..3L) {
        val output = dir.resolve("arith-$seed.jar")
        val result = Obfuscator(ObfuscationConfig(integerArithmetic = true, applicationPackages = listOf("app"),
          stringEncryption = false, numberObfuscation = false, renameClasses = false, renameMethods = false,
          renameFields = false, seed = seed, verifyOutput = true)).obfuscate(input, output)
        assertTrue(result.transformerReports.single { it.id == "integer-arithmetic" }.metrics.getValue("transformed") > 0)
        URLClassLoader(arrayOf(output.toUri().toURL()), null).use { loader ->
          val cls = loader.loadClass("app.Arithmetic")
          val ints = arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
          val longs = arrayOf(Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
          val random = Random(seed)
          val values = listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L, 1L) + List(300) { random.nextLong() }
          for ((a, b) in values.zip(values.reversed())) {
            assertEquals(original.getMethod("ints", *ints).invoke(null, a.toInt(), b.toInt()), cls.getMethod("ints", *ints).invoke(null, a.toInt(), b.toInt()))
            assertEquals(original.getMethod("longs", *longs).invoke(null, a, b), cls.getMethod("longs", *longs).invoke(null, a, b))
          }
          assertEquals(original.getMethod("sideEffects").invoke(null), cls.getMethod("sideEffects").invoke(null))
          assertEquals(123, cls.getField("trace").getInt(null))
          val doubles = arrayOf(Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
          for (value in listOf(Double.NaN, -0.0, 0.0, Double.POSITIVE_INFINITY, Double.MIN_VALUE)) {
            val expected = original.getMethod("floating", *doubles).invoke(null, value, -0.0) as Double
            val actual = cls.getMethod("floating", *doubles).invoke(null, value, -0.0) as Double
            assertEquals(expected.toRawBits(), actual.toRawBits())
          }
        }
      }
    }
  }
}
