package com.peco2282.obfaryn.benchmarks

import com.peco2282.obfaryn.api.protection.ControlFlowMode
import com.peco2282.obfaryn.api.protection.TransformationBudget
import com.peco2282.obfaryn.core.ObfuscationConfig
import com.peco2282.obfaryn.core.Obfuscator
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

/**
 * Builds the `bench.Workload` fixture once per JMH trial (via [build], called from
 * `@Setup(Level.Trial)`) and binds each measured method as a [MethodHandle] so a `@Benchmark`
 * measures only the call itself, not classloading or reflective lookup.
 *
 * Deliberately independent of `scripts/performance/`'s synthetic harnesses: those fork separate
 * JVMs and report percentiles across a whole tick workload or isolated VM dispatch; this exists to
 * isolate one transformation pass's per-call overhead under JMH's steady-state, JIT-warmed
 * measurement, which is a different kind of number entirely (see docs/performance.md).
 */
object ObfuscatedFixtures {
  private val SOURCE = """
    package bench;
    public final class Workload {
      private Workload() {}
      public static int plain(int x) { return x * 2 + 1; }
      public static String stringWork(int x) {
        String literal = "obfaryn-benchmark-literal-value";
        return literal.substring(0, 1 + Math.floorMod(x, literal.length() - 1));
      }
      public static int numberWork(int x) { return (x * 7919 + 104729) ^ 0x5bd1e995; }
      public static int controlFlowWork(int x) {
        int acc = 0;
        for (int i = 0; i < 8; i++) { if ((x & 1) == 0) acc += i; else acc -= i; x >>>= 1; }
        return acc;
      }
      private static int helper(int x) { return x + 1; }
      public static int callWork(int x) { return helper(helper(helper(x))); }
      public static int arrayWork(int x) {
        int[] values = new int[8];
        for (int i = 0; i < values.length; i++) values[i] = (x + i) * 3;
        int sum = 0;
        for (int i = 0; i < values.length; i++) sum += values[i];
        return sum;
      }
      public static int allocWork(int x) {
        Integer boxed = Integer.valueOf(x);
        int[] buffer = new int[4];
        buffer[0] = boxed.intValue();
        Object[] holder = new Object[] { buffer, boxed };
        return ((int[]) holder[0])[0] + holder.length;
      }
      public static int exceptionWork(int x) {
        try {
          if (x == 0) throw new IllegalStateException("zero");
          return 100 / x;
        } catch (IllegalStateException e) {
          return -1;
        } catch (ArithmeticException e) {
          return -2;
        }
      }
    }
  """.trimIndent()

  /** Full-match `owner.name(descriptor)` regexes for [ObfuscationConfig.virtualizeMethods]. */
  private val VIRTUALIZED_METHODS = listOf("plain", "arrayWork", "allocWork", "exceptionWork")
    .map { """bench/Workload\.$it\(I\)I""" }

  /** `baseline` is the plain `javac` output; `builds` holds one obfuscated variant per pass under test. */
  class Fixture(val baseline: Path, val builds: Map<String, Path>)

  private fun compile(dir: Path): Path {
    val source = dir.resolve("src/bench/Workload.java")
    Files.createDirectories(source.parent)
    Files.write(source, SOURCE.toByteArray())
    val classes = dir.resolve("classes")
    Files.createDirectories(classes)
    val diagnostics = java.io.ByteArrayOutputStream()
    val exit = ToolProvider.getSystemJavaCompiler()
      .run(null, java.io.PrintStream(diagnostics), java.io.PrintStream(diagnostics),
        "--release", "8", "-d", classes.toString(), source.toString())
    val produced = Files.walk(classes).use { it.filter { p -> p.toString().endsWith(".class") }.count() }
    check(exit == 0 && produced > 0L) {
      "benchmark fixture failed to compile (exit $exit, produced=$produced, source=$source exists=${Files.exists(source)}): ${diagnostics}"
    }
    val jar = dir.resolve("original.jar")
    JarOutputStream(Files.newOutputStream(jar)).use { out ->
      Files.walk(classes).use { paths ->
        paths.filter { it.toString().endsWith(".class") }.sorted().forEach {
          out.putNextEntry(JarEntry(classes.relativize(it).toString().replace('\\', '/')))
          out.write(Files.readAllBytes(it)); out.closeEntry()
        }
      }
    }
    return jar
  }

  // Renaming defaults to on; kept off here so every variant's class/method names stay exactly
  // "bench.Workload"/the declared method name, letting `handle`/`method` resolve the same way
  // for the baseline and every obfuscated variant without tracking a mapping file.
  private fun baseConfig() = ObfuscationConfig(
    applicationPackages = listOf("bench"), seed = 20261109L, verifyOutput = true,
    renameClasses = false, renameMethods = false, renameFields = false,
    transformationBudget = TransformationBudget(
      maxInputInstructions = 10000, maxInputBranches = 256, maxAddedInstructions = 4096, maxGrowthPercent = 400),
  )

  private fun obfuscate(dir: Path, name: String, original: Path, configure: ObfuscationConfig.() -> ObfuscationConfig): Path {
    val output = dir.resolve("$name.jar")
    val config = baseConfig().configure()
    try {
      Obfuscator(config).obfuscate(original, output)
    } catch (e: Exception) {
      throw IllegalStateException(
        "obfuscate($name) failed: original=$original exists=${Files.exists(original)} absolute=${original.toAbsolutePath()} " +
          "cwd=${java.nio.file.Paths.get("").toAbsolutePath()} classpath=${config.classpath} virtualizeMethods=${config.virtualizeMethods}", e)
    }
    return output
  }

  /** Builds the baseline and every single-pass variant once; call from one `@Setup(Level.Trial)`. */
  @JvmStatic
  fun build(): Fixture {
    val dir = Files.createTempDirectory("obfaryn-benchmarks-")
    val original = compile(dir)
    val builds = linkedMapOf(
      "stringEncryption" to obfuscate(dir, "string", original) { copy(stringEncryption = true) },
      "numberObfuscation" to obfuscate(dir, "number", original) { copy(numberObfuscation = true) },
      "controlFlow" to obfuscate(dir, "controlflow", original) { copy(controlFlowMode = ControlFlowMode.LIGHT) },
      "callIndirection" to obfuscate(dir, "callindirection", original) { copy(callIndirection = true) },
      "prelink" to obfuscate(dir, "prelink", original) { copy(callIndirection = true, prelinkCalls = true) },
      // Isolates VM-virtualization cost on 3 instruction shapes beyond the hot/tiny dispatch-only
      // pair scripts/performance/virtualization.py already covers: array access, allocation
      // (boxing, array and Object[] construction) and exception handling (throw/multi-catch).
      // `plain` is included too as the baseline "ordinary vs. virtualized method call" pair.
      "virtualized" to obfuscate(dir, "virtualized", original) { copy(virtualizeMethods = VIRTUALIZED_METHODS) },
    )
    return Fixture(original, builds)
  }

  /** `bench.Workload.<methodName>(int)` from [jar], resolved in a fresh isolated loader. */
  @JvmStatic
  fun method(jar: Path, methodName: String): java.lang.reflect.Method {
    val loader = URLClassLoader(arrayOf(jar.toUri().toURL()), ObfuscatedFixtures::class.java.classLoader)
    val type = try { loader.loadClass("bench.Workload") } catch (e: ClassNotFoundException) {
      val entries = if (Files.isRegularFile(jar)) java.util.jar.JarFile(jar.toFile()).use { f -> f.entries().asSequence().map { it.name }.toList() } else listOf("<missing: $jar>")
      throw IllegalStateException("bench.Workload not found in $jar (size=${if (Files.isRegularFile(jar)) Files.size(jar) else -1}); entries=$entries", e)
    }
    return type.getMethod(methodName, Int::class.javaPrimitiveType)
  }

  /** Binds `bench.Workload.<methodName>(int)` from [jar] as a bound [MethodHandle]; type `(int)int` or `(int)String`. */
  @JvmStatic
  fun handle(jar: Path, methodName: String): MethodHandle = MethodHandles.publicLookup().unreflect(method(jar, methodName))
}
