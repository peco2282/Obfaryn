package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.fixtures.JavaJarFixture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Path

/**
 * Regression for a classpath-construction bug in the full `Obfuscator`/`StandardPackage` facade:
 * `java.nio.file.Path` implements `Iterable<Path>`, so `context.classpath + context.input` (a
 * `List<Path> + Path`) resolved to Kotlin's `plus(Iterable<T>)` overload and silently exploded the
 * single input-JAR path into its filesystem segments (e.g. `/tmp/x/in.jar` -> `["tmp", "x",
 * "in.jar"]`) instead of appending it whole. `BridgeRegistry`'s classpath-based lookup then failed
 * on every exploded, nonexistent "path" with an uncaught `NoSuchFileException` opening it as a
 * `JarFile`, instead of resolving the class or falling back to the classloader cleanly.
 *
 * This only surfaced when a virtualized method's body references a class that is not already in
 * the run's own class map — virtually any call into the JDK standard library — which the existing
 * `Virtual*Test` fixtures never exercise through the full facade (they either construct
 * `VirtualizationTransformer` directly with their own `classpath`, bypassing the bug, or set
 * `virtualizationAnalyzeOnly = true`, which returns before the transformer ever runs). This test
 * goes through the public `Obfuscator` facade exactly as a real user would, with a method that
 * calls `Integer.valueOf`/`Integer#intValue` — a class outside the input JAR.
 */
class VirtualizationExternalClasspathTest {
  @TempDir lateinit var dir: Path

  @Test fun virtualizingAMethodThatReferencesAnExternalJdkClassSucceedsAndStaysCorrect() {
    val input = JavaJarFixture.compile(dir, mapOf(
      "app/Boxed" to """
        package app;
        public final class Boxed {
          // Bridging this call needs BridgeRegistry to resolve java.lang.Integer, which is not
          // among this JAR's own classes: exactly the path the classpath-construction bug broke.
          public static int roundTrip(int x) {
            Integer boxed = Integer.valueOf(x);
            int[] buffer = new int[2];
            buffer[0] = boxed.intValue();
            Object[] holder = new Object[] { buffer, boxed };
            return ((int[]) holder[0])[0] + holder.length;
          }
        }
      """.trimIndent(),
    ))
    val output = dir.resolve("output.jar")
    val config = ObfuscationConfig(
      applicationPackages = listOf("app"),
      virtualizeMethods = listOf("""app/Boxed\.roundTrip\(I\)I"""),
      renameClasses = false, renameMethods = false, renameFields = false,
      verifyOutput = true, seed = 20261109L,
    )
    val result = Obfuscator(config).obfuscate(input, output)
    assertEquals(1, result.methodsVirtualized, "the external-class reference must not block virtualization")

    URLClassLoader(arrayOf(input.toUri().toURL()), null).use { originalLoader ->
      URLClassLoader(arrayOf(output.toUri().toURL()), null).use { virtualizedLoader ->
        val original = originalLoader.loadClass("app.Boxed").getMethod("roundTrip", Int::class.javaPrimitiveType)
        val virtualized = virtualizedLoader.loadClass("app.Boxed").getMethod("roundTrip", Int::class.javaPrimitiveType)
        for (arg in intArrayOf(Int.MIN_VALUE, -1, 0, 1, 41, Int.MAX_VALUE)) {
          assertEquals(original.invoke(null, arg), virtualized.invoke(null, arg), "arg=$arg")
        }
      }
    }
  }
}
