package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.protection.ControlFlowMode
import com.peco2282.obfaryn.api.protection.TransformationBudget
import com.peco2282.obfaryn.core.fixtures.JavaJarFixture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.InvocationTargetException
import java.util.Random

/** Engine semantics fixture, deliberately independent of Bukkit and any consumer's game rules. */
class StatefulDifferentialTest {
  @TempDir lateinit var dir: Path

  @Test fun individualPassesAndConsumerCombinationPreserveEachOperationAndState() {
    val input = JavaJarFixture.compile(dir, mapOf(
      "contract/Machine" to """
        package contract; public interface Machine {
          String apply(int op, int a, int b); String snapshot();
        }
      """.trimIndent(),
      "app/Session" to """
        package app;
        public class Session implements contract.Machine {
          private static final int LIMIT = 3;
          private static final String READY = "ready\u0000\ud83c\udf00";
          private int phase, ticks, score, effects, cleanup;
          private long wide;
          private String event = READY;
          private int next(int n) { effects++; return n; }
          public String apply(int op, int a, int b) {
            switch (op) {
              case 0: phase=0; ticks=0; score=0; effects=0; cleanup=0; wide=0; event=READY; break;
              case 1:
                if (phase != 0) throw new IllegalStateException("start");
                phase=1; event="started"; break;
              case 2:
                if (phase != 1) throw new IllegalStateException("update");
                try {
                  if (a < 0) throw new IllegalArgumentException("negative");
                  ticks += a;
                  score += next(a) - next(b);
                  if (ticks >= LIMIT) { phase=2; event="ended"; }
                  else event="updated";
                } finally { cleanup++; }
                break;
              case 3:
                if (phase == 1 && ticks >= LIMIT) { phase=2; event="ended"; }
                break;
              case 4:
                score = ((next(a) + next(b)) ^ (a - b)) + (a ^ b);
                wide = (((long)a << b) + Long.MAX_VALUE) ^ (((long)b >>> a) - Long.MIN_VALUE);
                return score + ":" + wide + ":" + (a << b) + ":" + (a >> b) + ":" + (a >>> b);
              case 5:
                try { score = next(a) / next(b); }
                catch (ArithmeticException e) { event="division"; throw e; }
                finally { cleanup++; }
                break;
              case 6:
                event = READY == "ready\u0000\ud83c\udf00" ? "identity" : "broken"; break;
              default: throw new UnsupportedOperationException("op");
            }
            return snapshot();
          }
          public String snapshot() {
            return phase+":"+ticks+":"+score+":"+effects+":"+cleanup+":"+wide+":"+event;
          }
        }
      """.trimIndent(),
    ))
    val baseline = ObfuscationConfig(applicationPackages = listOf("app"),
      stringEncryption = false, numberObfuscation = false,
      renameClasses = false, renameMethods = false, renameFields = false, verifyOutput = true)
    val combined = baseline.copy(protectionLevel = ProtectionLevel.STRONG,
      stringEncryption = true, numberObfuscation = true,
      renameClasses = true, renameMethods = true, renameFields = true,
      flattenPackages = true, targetPackage = "app", stripGenericSignatures = true,
      integerArithmetic = true, maxArithmeticOperationsPerMethod = 4,
      controlFlowMode = ControlFlowMode.LIGHT, callIndirection = true, prelinkCalls = true,
      excludeVirtualization = listOf(".*"),
      transformationBudget = TransformationBudget(10000, 256, 1024, 300, 60000))
    val configs = linkedMapOf(
      "arithmetic" to baseline.copy(integerArithmetic = true, maxArithmeticOperationsPerMethod = 4),
      "flow" to baseline.copy(controlFlowMode = ControlFlowMode.LIGHT),
      "calls" to baseline.copy(callIndirection = true, prelinkCalls = true),
      "strings" to baseline.copy(stringEncryption = true),
      "numbers" to baseline.copy(numberObfuscation = true),
      "rename" to baseline.copy(renameClasses = true, renameMethods = true, renameFields = true,
        flattenPackages = true, targetPackage = "app"),
      "combined" to combined,
    )
    for ((name, config) in configs) for (seed in listOf(0L, 1L, 20261022L, Long.MIN_VALUE)) {
      val output = dir.resolve("$name-$seed.jar")
      val result = Obfuscator(config.copy(seed = seed)).obfuscate(input, output)
      assertEquals(0, result.methodsVirtualized)
      when (name) {
        "arithmetic", "combined" -> assertTrue(result.transformerReports.single { it.id == "integer-arithmetic" }.metrics.getValue("transformed") > 0)
        "flow" -> assertTrue(result.branchesObfuscated > 0)
        "calls" -> assertTrue(result.transformerReports.single { it.id == "call-indirection" }.metrics.getValue("indirectedCalls") > 0)
        "strings" -> assertTrue(result.stringsEncrypted > 0)
        "numbers" -> assertTrue(result.constantsObfuscated > 0)
        "rename" -> assertTrue(result.classesRenamed > 0 && result.fieldsRenamed > 0 && result.methodsRenamed > 0)
      }
      if (name == "combined") {
        assertTrue(result.stringsEncrypted > 0 && result.constantsObfuscated > 0)
        assertTrue(result.branchesObfuscated > 0)
        assertTrue(result.classesRenamed > 0 && result.methodsRenamed > 0 && result.fieldsRenamed > 0)
        assertTrue(result.transformerReports.single { it.id == "call-indirection" }.metrics.getValue("indirectedCalls") > 0)
      }
      val mapped = result.mapping?.let { Files.readAllLines(it) }.orEmpty()
        .firstOrNull { it.startsWith("app/Session -> ") }?.substringAfter(" -> ") ?: "app/Session"
      URLClassLoader(arrayOf(input.toUri().toURL()), null).use { original ->
        URLClassLoader(arrayOf(output.toUri().toURL()), null).use { transformed ->
          val left = Machine(original, "app.Session")
          val right = Machine(transformed, mapped.replace('/', '.'))
          fun step(op: Int, a: Int = 0, b: Int = 0, expected: String? = null) {
            val before = left.apply(op, a, b)
            assertEquals(before, right.apply(op, a, b), "$name seed=$seed op=$op a=$a b=$b")
            assertEquals(left.snapshot(), right.snapshot(), "state after op=$op a=$a b=$b")
            if (expected != null) assertEquals(expected, left.snapshot(), "independent state oracle")
          }
          step(0, expected="0:0:0:0:0:0:ready\u0000\uD83C\uDF00")
          step(1, expected="1:0:0:0:0:0:started")
          step(3, expected="1:0:0:0:0:0:started") // Starting alone must not end the session.
          step(2, -1, expected="1:0:0:0:1:0:started") // Throw, finally, no transition.
          step(2, 0, expected="1:0:0:2:2:0:updated")
          step(2, 2, expected="1:2:2:4:3:0:updated")
          step(2, 1, expected="2:3:3:6:4:0:ended") // Exactly the boundary.
          step(2, 1); step(1); step(6)
          val edges = listOf(Int.MIN_VALUE, Int.MAX_VALUE, -33, -32, -1, 0, 1, 31, 32, 33)
          for (a in edges) for (b in edges) { step(4, a, b); step(5, a, b) }
          val random = Random(seed)
          repeat(100) { step(4, random.nextInt(), random.nextInt()) }
          // Overflow is JVM wrapping, not mathematical/unbounded addition.
          step(0); step(1); step(2, 2); step(2, Int.MAX_VALUE)
          assertTrue(left.snapshot().startsWith("1:-2147483647:"))
          step(0)
          step(4, 1, 2, expected="0:0:-1:2:0:2:ready\u0000\uD83C\uDF00")
          assertEquals(listOf("return", "-1:2:4:0:0"), left.apply(4, 1, 2))
          assertEquals(listOf("return", "-1:2:4:0:0"), right.apply(4, 1, 2))
          assertEquals(listOf("throw", "java.lang.ArithmeticException", "/ by zero"), left.apply(5, 1, 0))
          assertEquals(listOf("throw", "java.lang.ArithmeticException", "/ by zero"), right.apply(5, 1, 0))
          assertEquals(left.snapshot(), right.snapshot())
          step(99)
        }
      }
    }
  }

  private class Machine(loader: ClassLoader, name: String) {
    private val instance = loader.loadClass(name).getConstructor().newInstance()
    private val api = loader.loadClass("contract.Machine")
    private val apply = api.getMethod("apply", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val snapshot = api.getMethod("snapshot")
    fun snapshot(): String = snapshot.invoke(instance) as String
    fun apply(op: Int, a: Int, b: Int): Any = try {
      listOf("return", apply.invoke(instance, op, a, b))
    } catch (e: InvocationTargetException) {
      listOf("throw", e.targetException.javaClass.name, e.targetException.message)
    }
  }
}
