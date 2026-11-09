package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.protection.ControlFlowMode
import com.peco2282.obfaryn.api.protection.TransformationBudget
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

/**
 * Differential coverage for JVM/Java constructs not exercised by the other stateful fixtures:
 * sealed interfaces with record-pattern `switch`, `invokedynamic` lambdas and method references,
 * `MethodHandle`/`VarHandle`, `ServiceLoader` (with a packaged `META-INF/services` provider
 * configuration file), try-with-resources (including suppressed-exception ordering), and
 * `volatile`/`Atomic*` state. Sources compile at `--release 21` (pattern matching for `switch`,
 * JEP 441, needs no preview flag there) so the engine's handling of newer class-file constructs is
 * exercised alongside the Java 8 baseline covered elsewhere.
 *
 * Original and transformed classes are loaded in isolated loaders and driven through identical
 * scenarios; results and public contracts (ServiceLoader-visible provider, suppressed exceptions)
 * are compared directly.
 */
class JavaAdvancedConstructsDifferentialTest {
  @TempDir lateinit var dir: Path

  private fun compileAndPackage(sources: Map<String, String>, resources: Map<String, String> = emptyMap()): Path {
    val files = sources.map { (name, source) ->
      dir.resolve("sources/$name.java").also { Files.createDirectories(it.parent); Files.write(it, source.toByteArray()) }
    }
    val classes = dir.resolve("classes-${sources.hashCode()}"); Files.createDirectories(classes)
    val diagnostics = ByteArrayOutputStream()
    val args = listOf("--release", "21", "-d", classes.toString()) + files.map(Path::toString)
    val exit = ToolProvider.getSystemJavaCompiler().run(null, ByteArrayOutputStream(), diagnostics, *args.toTypedArray())
    assertEquals(0, exit, diagnostics.toString())
    val input = dir.resolve("input-${sources.hashCode()}.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      Files.walk(classes).use { paths ->
        paths.filter { it.toString().endsWith(".class") }.sorted().forEach {
          jar.putNextEntry(JarEntry(classes.relativize(it).toString().replace('\\', '/')))
          jar.write(Files.readAllBytes(it)); jar.closeEntry()
        }
      }
      for ((path, content) in resources) {
        jar.putNextEntry(JarEntry(path)); jar.write(content.toByteArray()); jar.closeEntry()
      }
    }
    return input
  }

  @Test fun sealedPatternsHandlesServiceLoaderResourcesAndConcurrentStateArePreserved() {
    val input = compileAndPackage(
      sources = mapOf(
        "contract/Workload" to """
          package contract;
          public interface Workload { String run(int scenario, int arg) throws Throwable; }
        """.trimIndent(),
        "app/Shape" to """
          package app;
          public sealed interface Shape permits Shape.Circle, Shape.Square, Shape.Rect {
            record Circle(double r) implements Shape {}
            record Square(double side) implements Shape {}
            record Rect(double w, double h) implements Shape {}
          }
        """.trimIndent(),
        "app/Shapes" to """
          package app;
          public final class Shapes {
            // Pattern matching for switch (JEP 441) with record deconstruction patterns.
            static String describe(Shape shape) {
              return switch (shape) {
                case Shape.Circle(double r) when r <= 0 -> "degenerate-circle";
                case Shape.Circle(double r) -> "circle:" + (Math.PI * r * r);
                case Shape.Square(double s) -> "square:" + (s * s);
                case Shape.Rect(double w, double h) -> "rect:" + (w * h);
              };
            }
            static Shape pick(int arg) {
              return switch (Math.floorMod(arg, 3)) {
                case 0 -> new Shape.Circle(Math.floorMod(arg, 7) - 1);
                case 1 -> new Shape.Square(Math.floorMod(arg, 11));
                default -> new Shape.Rect(Math.floorMod(arg, 5), Math.floorMod(arg, 9));
              };
            }
          }
        """.trimIndent(),
        "app/Functional" to """
          package app;
          import java.util.function.Function;
          import java.util.function.IntBinaryOperator;
          public final class Functional {
            static int square(int x) { return x * x; }
            // invokedynamic: method reference and capturing lambda metafactories.
            static String refsAndLambdas(int arg) {
              Function<Integer, Integer> ref = Functional::square;
              IntBinaryOperator add = (a, b) -> a + b;
              int captured = arg;
              Function<Integer, Integer> capturing = x -> x + captured;
              return ref.apply(arg) + ":" + add.applyAsInt(arg, 3) + ":" + capturing.apply(1);
            }
          }
        """.trimIndent(),
        "app/Handles" to """
          package app;
          import java.lang.invoke.MethodHandle;
          import java.lang.invoke.MethodHandles;
          import java.lang.invoke.VarHandle;
          public final class Handles {
            static volatile int flag;
            static String methodHandle(int arg) throws Throwable {
              MethodHandle mh = MethodHandles.lookup().findStatic(Functional.class, "square",
                java.lang.invoke.MethodType.methodType(int.class, int.class));
              return String.valueOf((int) mh.invoke(arg));
            }
            static String varHandle(int arg) throws Throwable {
              VarHandle vh = MethodHandles.lookup().findStaticVarHandle(Handles.class, "flag", int.class);
              vh.set(arg);
              boolean cas = vh.compareAndSet(arg, arg + 1);
              int after = (int) vh.getVolatile();
              return cas + ":" + after;
            }
          }
        """.trimIndent(),
        "app/Resources" to """
          package app;
          import java.util.ArrayList;
          import java.util.List;
          public final class Resources {
            static final class Tracked implements AutoCloseable {
              final String id; final List<String> log; final boolean failOnClose;
              Tracked(String id, List<String> log, boolean failOnClose) { this.id = id; this.log = log; this.failOnClose = failOnClose; }
              public void close() { log.add("close:" + id); if (failOnClose) throw new IllegalStateException("close-" + id); }
            }
            // try-with-resources closes in reverse declaration order; a primary exception from the
            // body keeps later close()-thrown exceptions as *suppressed*, not replacing it.
            static String tryWithResources(int arg) {
              List<String> log = new ArrayList<>();
              boolean primary = (arg & 1) == 0;
              try (Tracked a = new Tracked("a", log, arg % 3 == 0); Tracked b = new Tracked("b", log, arg % 5 == 0)) {
                log.add("body");
                if (primary) throw new RuntimeException("primary");
              } catch (RuntimeException e) {
                return log + ":" + e.getMessage() + ":" + e.getSuppressed().length;
              }
              return log + ":none:0";
            }
          }
        """.trimIndent(),
        "app/spi/Greeter" to """
          package app.spi;
          public interface Greeter { String greet(); }
        """.trimIndent(),
        "app/spi/GreeterImpl" to """
          package app.spi;
          public final class GreeterImpl implements Greeter { public String greet() { return "hello-spi"; } }
        """.trimIndent(),
        "app/Services" to """
          package app;
          import java.util.ServiceLoader;
          public final class Services {
            static String load() {
              StringBuilder out = new StringBuilder();
              for (app.spi.Greeter g : ServiceLoader.load(app.spi.Greeter.class, Services.class.getClassLoader()))
                out.append(g.greet());
              return out.toString();
            }
          }
        """.trimIndent(),
        "app/Atomics" to """
          package app;
          import java.util.concurrent.atomic.AtomicInteger;
          import java.util.concurrent.atomic.AtomicReference;
          public final class Atomics {
            static String run(int arg) {
              AtomicInteger counter = new AtomicInteger(arg);
              int previous = counter.getAndIncrement();
              int updated = counter.updateAndGet(v -> v * 2);
              AtomicReference<String> ref = new AtomicReference<>("init");
              boolean cas = ref.compareAndSet("init", "updated-" + arg);
              return previous + ":" + updated + ":" + cas + ":" + ref.get();
            }
          }
        """.trimIndent(),
        "app/Harness" to """
          package app;
          public final class Harness implements contract.Workload {
            public String run(int scenario, int arg) throws Throwable {
              return switch (scenario) {
                case 0 -> Shapes.describe(Shapes.pick(arg));
                case 1 -> Functional.refsAndLambdas(arg);
                case 2 -> Handles.methodHandle(arg);
                case 3 -> Handles.varHandle(arg);
                case 4 -> Resources.tryWithResources(arg);
                case 5 -> Services.load();
                case 6 -> Atomics.run(arg);
                default -> throw new UnsupportedOperationException("scenario");
              };
            }
          }
        """.trimIndent(),
      ),
      resources = mapOf("META-INF/services/app.spi.Greeter" to "app.spi.GreeterImpl\n"),
    )

    val config = ObfuscationConfig(
      applicationPackages = listOf("app"),
      protectionLevel = ProtectionLevel.STRONG,
      stringEncryption = true, numberObfuscation = true,
      renameClasses = true, renameMethods = true, renameFields = true,
      flattenPackages = true, targetPackage = "app", stripGenericSignatures = true,
      integerArithmetic = true, maxArithmeticOperationsPerMethod = 4,
      controlFlowMode = ControlFlowMode.LIGHT, callIndirection = true, prelinkCalls = true,
      excludeVirtualization = listOf(".*"), verifyOutput = true,
      transformationBudget = TransformationBudget(10000, 256, 1024, 300, 60000),
    )

    for (seed in listOf(0L, 1L, 20261022L, Long.MIN_VALUE)) {
      val output = dir.resolve("adv-$seed.jar")
      val result = Obfuscator(config.copy(seed = seed)).obfuscate(input, output)
      assertEquals(0, result.methodsVirtualized)
      assertTrue(result.classesRenamed > 0 && result.methodsRenamed > 0, "rename pass must apply for seed=$seed")

      // `contract.*`/`app.spi.*` stay reachable to the ServiceLoader mechanism by name; the
      // scenario dispatcher `app.Harness` is renamed and resolved through the mapping.
      val mappedHarness = Files.readAllLines(requireNotNull(result.mapping))
        .first { it.startsWith("app/Harness -> ") }.substringAfter(" -> ").replace('/', '.')

      URLClassLoader(arrayOf(input.toUri().toURL()), null).use { originalLoader ->
        URLClassLoader(arrayOf(output.toUri().toURL()), null).use { transformedLoader ->
          val left = Workload(originalLoader, "app.Harness")
          val right = Workload(transformedLoader, mappedHarness)

          fun differential(scenario: Int, arg: Int) {
            val a = left.run(scenario, arg)
            val b = right.run(scenario, arg)
            assertEquals(a, b, "seed=$seed scenario=$scenario arg=$arg")
          }

          val args = intArrayOf(Int.MIN_VALUE, -97, -11, -1, 0, 1, 2, 3, 5, 7, 9, 11, 23, 1000, Int.MAX_VALUE)
          for (arg in args) {
            differential(0, arg) // sealed interface + record-pattern switch
            differential(1, arg) // invokedynamic lambdas / method references
            differential(2, arg) // MethodHandle
            differential(3, arg) // VarHandle (static field; serialized by running seeds sequentially)
            differential(4, arg) // try-with-resources + suppressed exceptions
            differential(6, arg) // Atomic* state
          }
          // ServiceLoader resolution is argument-independent and spans both loaders by the same
          // provider-configuration file packaged into the input and transformed artifacts alike.
          differential(5, 0)

          // Independent oracles at fixed arguments, spot-checked against the engine-agnostic contract.
          assertEquals(listOf("return", "circle:12.566370614359172"), left.run(0, 3))
          assertEquals(listOf("return", "circle:12.566370614359172"), right.run(0, 3))
          assertEquals(listOf("return", "[body, close:b, close:a]:primary:2"), left.run(4, 0))
          assertEquals(listOf("return", "hello-spi"), left.run(5, 0))
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
