package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.protection.ControlFlowMode
import com.peco2282.obfaryn.api.protection.TransformationBudget
import com.peco2282.obfaryn.core.fixtures.JavaJarFixture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Differential coverage for language and runtime constructs the earlier stateful fixtures do not
 * exercise: deep and mutual recursion, multidimensional array identity, enum `switch`/`values`,
 * interface default-method (diamond) dispatch, `synchronized` sections, and genuinely multithreaded
 * contention over concurrent collections.
 *
 * Original and transformed classes are loaded in isolated loaders and driven through identical
 * scenarios. Deterministic scenarios compare the value and an independent oracle. The concurrent
 * scenario is non-deterministic in interleaving, so it is checked by invariant and converged final
 * state only (never by log/order equality), matching the semantic-validation policy.
 */
class ConcurrencyAndLanguageDifferentialTest {
  @TempDir lateinit var dir: Path

  @Test fun recursionArraysEnumsDefaultsSynchronizationAndConcurrencyArePreserved() {
    val input = JavaJarFixture.compile(dir, mapOf(
      "contract/Workload" to """
        package contract;
        public interface Workload { String run(int scenario, int arg) throws Exception; }
      """.trimIndent(),
      "app/Base" to """
        package app;
        // Diamond: two default methods merged by an explicit override that calls both supers.
        interface Named { default String tag() { return "base"; } }
        interface Loud extends Named { default String tag() { return "LOUD:" + Named.super.tag(); } }
        interface Soft extends Named { default String tag() { return "soft:" + Named.super.tag(); } }
        public class Base implements Loud, Soft {
          public String tag() { return Loud.super.tag() + "|" + Soft.super.tag(); }
        }
      """.trimIndent(),
      "app/Ops" to """
        package app;
        enum Op {
          ADD { int eval(int a, int b) { return a + b; } },
          SUB { int eval(int a, int b) { return a - b; } },
          MUL { int eval(int a, int b) { return a * b; } };
          abstract int eval(int a, int b);
        }
        public final class Ops {
          // Mutual recursion exercising two frames bouncing through transformed call sites.
          private static boolean isEven(int n) { return n == 0 ? true : isOdd(n - 1); }
          private static boolean isOdd(int n) { return n == 0 ? false : isEven(n - 1); }
          private static long fib(int n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }

          static String recursion(int arg) {
            int n = Math.floorMod(arg, 24);
            return isEven(n) + ":" + isOdd(n) + ":" + fib(n);
          }

          static String arrays(int arg) {
            int size = 1 + Math.floorMod(arg, 6);
            int[][] grid = new int[size][size];
            for (int i = 0; i < size; i++)
              for (int j = 0; j < size; j++)
                grid[i][j] = (i * 31 + j) ^ arg;
            int[][] copy = grid.clone();                 // shallow: rows shared
            int[] firstRow = java.util.Arrays.copyOf(grid[0], size);
            copy[0] = firstRow;                           // detach only row 0
            long sum = 0;
            for (int[] row : grid) for (int v : row) sum += v;
            boolean sharedRow = grid.length > 1 && copy[1] == grid[1];
            boolean detachedRow = copy[0] != grid[0];
            return sum + ":" + sharedRow + ":" + detachedRow + ":" + java.util.Arrays.deepHashCode(grid);
          }

          static String enums(int arg) {
            Op op = Op.values()[Math.floorMod(arg, Op.values().length)];
            int a = arg, b = arg >> 1;
            int folded;
            switch (op) {
              case ADD: folded = op.eval(a, b) + 1; break;
              case SUB: folded = op.eval(a, b) + 2; break;
              case MUL: folded = op.eval(a, b) + 3; break;
              default: throw new IllegalStateException();
            }
            Op parsed = Op.valueOf(op.name());
            return op.name() + ":" + op.ordinal() + ":" + folded + ":" + (parsed == op);
          }

          static String synchronizedCounter(int arg) {
            final Object lock = new Object();
            final int[] cell = new int[1];
            int rounds = 64 + Math.floorMod(arg, 64);
            for (int i = 0; i < rounds; i++) synchronized (lock) { cell[0] += (i ^ arg) & 7; }
            return rounds + ":" + cell[0];
          }
        }
      """.trimIndent(),
      "app/Harness" to """
        package app;
        import java.util.concurrent.ConcurrentHashMap;
        import java.util.concurrent.atomic.AtomicLong;
        import java.util.concurrent.CountDownLatch;
        public class Harness implements contract.Workload {
          private final Base base = new Base();
          public String run(int scenario, int arg) throws Exception {
            switch (scenario) {
              case 0: return base.tag();
              case 1: return Ops.recursion(arg);
              case 2: return Ops.arrays(arg);
              case 3: return Ops.enums(arg);
              case 4: return Ops.synchronizedCounter(arg);
              case 5: return concurrent(arg);
              default: throw new UnsupportedOperationException("scenario");
            }
          }
          // Non-deterministic interleaving, deterministic final aggregate: threads contend on a
          // shared concurrent map and atomic. The returned value is interleaving-independent.
          private String concurrent(int arg) throws Exception {
            final int threads = 8;
            final int perThread = 500 + Math.floorMod(arg, 500);
            final ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<Integer, Integer>();
            final AtomicLong total = new AtomicLong();
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(threads);
            Thread[] pool = new Thread[threads];
            for (int t = 0; t < threads; t++) {
              final int id = t;
              pool[t] = new Thread(new Runnable() {
                public void run() {
                  try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                      int key = (i * 7 + id) & 15;
                      map.merge(key, 1, new java.util.function.BiFunction<Integer, Integer, Integer>() {
                        public Integer apply(Integer a, Integer b) { return a + b; }
                      });
                      total.addAndGet((id + 1L) * (i & 3));
                    }
                  } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                  finally { done.countDown(); }
                }
              });
              pool[t].start();
            }
            start.countDown();
            done.await();
            long mapped = 0;
            for (int v : map.values()) mapped += v;
            return threads + ":" + perThread + ":" + mapped + ":" + total.get() + ":" + map.size();
          }
        }
      """.trimIndent(),
    ))

    val config = ObfuscationConfig(applicationPackages = listOf("app"),
      protectionLevel = ProtectionLevel.STRONG,
      stringEncryption = true, numberObfuscation = true,
      renameClasses = true, renameMethods = true, renameFields = true,
      flattenPackages = true, targetPackage = "app", stripGenericSignatures = true,
      integerArithmetic = true, maxArithmeticOperationsPerMethod = 4,
      controlFlowMode = ControlFlowMode.LIGHT, callIndirection = true, prelinkCalls = true,
      excludeVirtualization = listOf(".*"), verifyOutput = true,
      transformationBudget = TransformationBudget(10000, 256, 1024, 300, 60000))

    for (seed in listOf(0L, 1L, 20261022L, Long.MIN_VALUE)) {
      val output = dir.resolve("workload-$seed.jar")
      val result = Obfuscator(config.copy(seed = seed)).obfuscate(input, output)
      assertEquals(0, result.methodsVirtualized)
      assertTrue(result.classesRenamed > 0 && result.methodsRenamed > 0,
        "rename pass must apply for seed=$seed")

      // `contract.*` stays outside applicationPackages, so the interface keeps its name; the
      // implementation in `app` is renamed and resolved through the mapping, as in the other
      // stateful differential fixtures.
      val mappedHarness = Files.readAllLines(requireNotNull(result.mapping))
        .first { it.startsWith("app/Harness -> ") }.substringAfter(" -> ").replace('/', '.')

      URLClassLoader(arrayOf(input.toUri().toURL()), null).use { originalLoader ->
        URLClassLoader(arrayOf(output.toUri().toURL()), null).use { transformedLoader ->
          val left = Workload(originalLoader, "app.Harness")
          val right = Workload(transformedLoader, mappedHarness)

          fun differential(scenario: Int, arg: Int, oracle: String? = null) {
            val a = left.run(scenario, arg)
            val b = right.run(scenario, arg)
            assertEquals(a, b, "seed=$seed scenario=$scenario arg=$arg")
            if (oracle != null) assertEquals(listOf("return", oracle), a, "independent oracle scenario=$scenario arg=$arg")
          }

          // Diamond default-method dispatch is argument-free and fully deterministic.
          differential(0, 0, oracle = "LOUD:base|soft:base")

          val args = intArrayOf(Int.MIN_VALUE, -97, -1, 0, 1, 2, 5, 23, 1000, Int.MAX_VALUE)
          for (arg in args) {
            differential(1, arg) // recursion
            differential(2, arg) // arrays
            differential(3, arg) // enums
            differential(4, arg) // synchronized
          }

          // Spot-check oracles for the deterministic scenarios at a fixed argument.
          differential(1, 10, oracle = "true:false:55")          // fib(10)=55, 10 even
          differential(3, 0, oracle = "ADD:0:1:true")            // 0+0 +1

          // Concurrency: run both, assert equality and the interleaving-independent invariant that
          // every increment landed (mapped total == threads*perThread) for each argument.
          for (arg in intArrayOf(0, 1, 250, 499)) {
            val a = left.run(5, arg)
            val b = right.run(5, arg)
            assertEquals(a, b, "concurrent final state seed=$seed arg=$arg")
            val parts = ((a as List<*>)[1] as String).split(":")
            val threads = parts[0].toInt(); val perThread = parts[1].toInt(); val mapped = parts[2].toLong()
            assertEquals(threads.toLong() * perThread, mapped,
              "no increment may be lost under contention seed=$seed arg=$arg")
          }
        }
      }
    }
  }

  private class Workload(loader: ClassLoader, harness: String) {
    private val api = loader.loadClass("contract.Workload")
    private val instance = loader.loadClass(harness).getConstructor().newInstance()
    private val run = api.getMethod("run", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    fun run(scenario: Int, arg: Int): Any = try {
      listOf("return", run.invoke(instance, scenario, arg))
    } catch (e: InvocationTargetException) {
      listOf("throw", e.targetException.javaClass.name, e.targetException.message)
    }
  }
}
