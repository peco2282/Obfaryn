package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.protection.ControlFlowMode
import com.peco2282.obfaryn.api.protection.TransformationBudget
import com.peco2282.obfaryn.core.fixtures.JavaJarFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * Seeded fuzz testing (Phase 5-2). Generates JVM-spec-valid Java source -- never raw bytecode, so
 * it cannot itself produce a class the compiler would reject -- with randomized arithmetic,
 * branches, loops, arrays, exceptions, method calls, a small class hierarchy and numeric
 * conversions, then runs the *same* generated program's original and obfuscated form side by
 * side through a representative combined pipeline (string/number obfuscation, lightweight control
 * flow, call indirection + prelink, integer arithmetic, and one explicitly virtualized method).
 *
 * Reproducible: the master seed is printed every run; set `OBFARYN_FUZZ_SEED` to replay one from a
 * CI log locally. Scale: `OBFARYN_FUZZ_CASES` (default 8) controls how many generated programs run;
 * a manual/scheduled CI job can set this far higher without changing the default, fast, per-push
 * run. Minimized on failure: a divergence is re-run at shrinking `size` values (same case seed)
 * until the smallest value that still reproduces it, and that generated source is written under
 * `build/fuzz-failures/` for turning into a permanent regression test.
 */
class FuzzDifferentialTest {
  @TempDir lateinit var dir: Path

  /**
   * One call shape per listed fuzz area: arithmetic, branches, loops, arrays, exceptions, method
   * calls, class hierarchy, numeric conversions. `size` scales loop bounds/array lengths only, so
   * shrinking it for minimization keeps the same operators/branches/divisor and only reduces how
   * much work each loop does.
   */
  private class Program(random: Random, size: Int) {
    private val arrayLength = 2 + random.nextInt(size.coerceAtLeast(1))
    private val loopBound = 1 + random.nextInt(size.coerceAtLeast(1))
    private val divisor = listOf(-7, -3, -1, 1, 2, 5, 11).random(random)
    private val shift = random.nextInt(32)
    private val arithmeticOp = listOf("+", "-", "*", "^", "&", "|").random(random)
    private val compareOp = listOf("<", "<=", ">", ">=", "==", "!=").random(random)

    fun source(): String = """
      package fuzz;
      public class Program {
        static class Base {
          int tag() { return 1; }
          int combine(int a, int b) { return a $arithmeticOp b; }
        }
        static class Derived extends Base {
          @Override int tag() { return 2; }
          @Override int combine(int a, int b) { return super.combine(a, b) $arithmeticOp 3; }
        }
        // Arithmetic + numeric conversions.
        static long widen(int a, int b) {
          long wide = (long) a $arithmeticOp (long) b;
          double asDouble = (double) wide / $divisor;
          return (long) asDouble + (int) (wide >>> $shift);
        }
        // Branches.
        static int classify(int v) {
          if (v $compareOp $divisor) return v % 2 == 0 ? 1 : -1;
          else if (v == 0) return 0;
          return v > 0 ? 2 : -2;
        }
        // Loops + arrays.
        static int sumArray(int seed) {
          int[] values = new int[$arrayLength];
          for (int i = 0; i < values.length; i++) values[i] = seed + i * $loopBound;
          int total = 0;
          for (int i = 0; i < $loopBound; i++) {
            for (int j = 0; j < values.length; j++) total += values[j] ^ i;
          }
          return total;
        }
        // Exceptions: throws whenever `a` is a multiple of the divisor.
        static int finallyCounter = 0;
        static int guardedDivide(int a) {
          try {
            int d = a % $divisor;
            return a / d;
          } catch (ArithmeticException e) {
            return -99;
          } finally {
            finallyCounter++;
          }
        }
        // Method calls + polymorphic dispatch.
        static int dispatch(int a, int b) {
          Base[] instances = new Base[] { new Base(), new Derived() };
          int acc = 0;
          for (Base instance : instances) acc += instance.tag() * instance.combine(a, b);
          return acc;
        }
        public static String run(int seed) {
          return widen(seed, seed + 1) + ":" + classify(seed) + ":" + sumArray(seed) + ":" +
            guardedDivide(seed) + ":" + dispatch(seed, seed - 1) + ":" + finallyCounter;
        }
      }
    """.trimIndent()
  }

  private fun obfuscateAndCompare(caseDir: Path, caseSeed: Long, size: Int): String? {
    Files.createDirectories(caseDir)
    val source = Program(Random(caseSeed), size).source()
    val input = JavaJarFixture.compile(caseDir, mapOf("fuzz/Program" to source))
    val output = caseDir.resolve("output.jar")
    val config = ObfuscationConfig(
      applicationPackages = listOf("fuzz"),
      protectionLevel = ProtectionLevel.STRONG,
      stringEncryption = true, numberObfuscation = true,
      renameClasses = false, renameMethods = false, renameFields = false,
      integerArithmetic = true, maxArithmeticOperationsPerMethod = 4,
      controlFlowMode = ControlFlowMode.LIGHT, callIndirection = true, prelinkCalls = true,
      virtualizeMethods = listOf("""fuzz/Program\.sumArray\(I\)I"""),
      verifyOutput = true, seed = caseSeed,
      transformationBudget = TransformationBudget(
        maxInputInstructions = 10000, maxInputBranches = 256, maxAddedInstructions = 4096, maxGrowthPercent = 400),
    )
    Obfuscator(config).obfuscate(input, output)
    URLClassLoader(arrayOf(input.toUri().toURL()), null).use { originalLoader ->
      URLClassLoader(arrayOf(output.toUri().toURL()), null).use { obfuscatedLoader ->
        val originalRun = originalLoader.loadClass("fuzz.Program").getMethod("run", Int::class.javaPrimitiveType)
        val obfuscatedRun = obfuscatedLoader.loadClass("fuzz.Program").getMethod("run", Int::class.javaPrimitiveType)
        for (arg in intArrayOf(caseSeed.toInt(), 0, -1, 1, Int.MIN_VALUE, Int.MAX_VALUE)) {
          fun invoke(method: java.lang.reflect.Method): Any = try {
            listOf("return", method.invoke(null, arg))
          } catch (e: InvocationTargetException) {
            listOf("throw", e.targetException.javaClass.name, e.targetException.message)
          }
          val a = invoke(originalRun)
          val b = invoke(obfuscatedRun)
          if (a != b) return "arg=$arg original=$a obfuscated=$b"
        }
      }
    }
    return null
  }

  /** Re-runs the same case seed at shrinking sizes; returns the smallest size that still fails. */
  private fun minimize(caseSeed: Long, size: Int): Int {
    var smallest = size
    for (candidate in 1 until size) {
      val reproduced = try {
        obfuscateAndCompare(dir.resolve("minimize-$caseSeed-$candidate"), caseSeed, candidate) != null
      } catch (_: Exception) {
        true // A different failure (e.g. budget rejection) at a smaller size is still worth keeping.
      }
      if (reproduced) { smallest = candidate; break }
    }
    return smallest
  }

  private fun saveFailure(masterSeed: Long, caseSeed: Long, size: Int, failure: String) {
    val failures = java.nio.file.Paths.get("build/fuzz-failures")
    Files.createDirectories(failures)
    val source = Program(Random(caseSeed), size).source()
    val path = failures.resolve("case-$caseSeed-size$size.java")
    Files.write(path, ("""
      // Obfaryn FuzzDifferentialTest regression candidate.
      // masterSeed=$masterSeed caseSeed=$caseSeed size=$size
      // Divergence: $failure
      // To turn into a permanent regression test, reproduce with:
      //   FuzzDifferentialTest().obfuscateAndCompareForDebugging($caseSeed, $size)
      $source
    """.trimIndent()).toByteArray())
    println("Fuzz failure minimized and saved to $path")
  }

  @Test fun seededFuzzFindsNoDivergenceBetweenOriginalAndObfuscatedPrograms() {
    val masterSeed = System.getenv("OBFARYN_FUZZ_SEED")?.toLongOrNull() ?: System.nanoTime()
    val caseCount = System.getenv("OBFARYN_FUZZ_CASES")?.toIntOrNull() ?: 8
    println("FuzzDifferentialTest master seed: $masterSeed ($caseCount cases; set OBFARYN_FUZZ_SEED to replay)")
    val master = Random(masterSeed)
    for (case in 0 until caseCount) {
      val caseSeed = master.nextLong()
      val size = 3 + master.nextInt(12)
      val failure = obfuscateAndCompare(dir.resolve("case-$case"), caseSeed, size)
      if (failure != null) {
        val minimizedSize = minimize(caseSeed, size)
        saveFailure(masterSeed, caseSeed, minimizedSize, failure)
        throw AssertionError("Fuzz divergence at masterSeed=$masterSeed caseSeed=$caseSeed (minimized size=$minimizedSize, original size=$size): $failure")
      }
    }
  }
}
