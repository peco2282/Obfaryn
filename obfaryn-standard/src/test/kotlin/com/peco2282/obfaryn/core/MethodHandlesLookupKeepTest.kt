package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.protection.ControlFlowMode
import com.peco2282.obfaryn.core.fixtures.JavaJarFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Companion to [ReflectionScannerKeepTest] for the `MethodHandles.Lookup` finders the scanner also
 * understands. A field reached by a constant-string `findGetter` and a method reached by a
 * constant-string `findVirtual` are kept, so the handles still bind through the fully renamed JAR,
 * while unrelated members are renamed and a non-literal finder name is not kept.
 */
class MethodHandlesLookupKeepTest {
  @TempDir lateinit var dir: Path

  private val sources = mapOf(
    "mh/Target" to """
      package mh;
      public final class Target {
        public int value;    // findGetter literal -> kept
        private int hidden;  // never reflected -> renamed

        public Target(int id) { this.value = id * 3; this.hidden = id; }
        public int greet(int x) { return x + value + secret(); }  // findVirtual literal -> kept
        private int secret() { return 11; }                       // renamed
        public int dyn(int x) { return x; }                       // reached by a non-literal name -> renamed
      }
    """.trimIndent(),
    "mh/Workload" to """
      package mh;
      import java.lang.invoke.MethodHandle;
      import java.lang.invoke.MethodHandles;
      import java.lang.invoke.MethodType;
      public final class Workload {
        public static String roundTrip(int id) throws Throwable {
          MethodHandles.Lookup lookup = MethodHandles.lookup();
          MethodHandle getter = lookup.findGetter(Target.class, "value", int.class);
          MethodHandle greet = lookup.findVirtual(Target.class, "greet", MethodType.methodType(int.class, int.class));
          Target t = new Target(id);
          int v = (int) getter.invoke(t);
          int g = (int) greet.invoke(t, id);
          return "v=" + v + ",g=" + g;
        }
        public static String roundTripDynamic(int id) throws Throwable {
          MethodHandles.Lookup lookup = MethodHandles.lookup();
          // Not a single constant-string name, so the scanner cannot keep `dyn`.
          MethodHandle h = lookup.findVirtual(Target.class, new String("dyn"), MethodType.methodType(int.class, int.class));
          return String.valueOf((int) h.invoke(new Target(id), id));
        }
      }
    """.trimIndent(),
  )

  @Test fun literalLookupTargetsAreKeptWhileOtherMembersAreRenamed() {
    val input = JavaJarFixture.compile(dir, sources)

    for (seed in listOf(0L, 1L, 20261118L, Long.MIN_VALUE)) {
      val output = dir.resolve("mh-$seed.jar")
      val result = Obfuscator(
        ObfuscationConfig(
          applicationPackages = listOf("mh"),
          seed = seed,
          protectionLevel = ProtectionLevel.STRONG,
          controlFlowMode = ControlFlowMode.LIGHT,
          renameClasses = true,
          renameMethods = true,
          renameFields = true,
          flattenPackages = true,
          targetPackage = "hidden",
          keep = listOf(
            "mh.Workload#roundTrip(I)Ljava/lang/String;",
            "mh.Workload#roundTripDynamic(I)Ljava/lang/String;",
          ),
          verifyOutput = true,
          excludeVirtualization = listOf(".*"),
        )
      ).obfuscate(input, output)
      assertTrue(result.classesRenamed > 0, "seed=$seed: classes must be renamed")
      assertTrue(result.fieldsRenamed > 0, "seed=$seed: non-reflected fields must be renamed")
      assertTrue(result.methodsRenamed > 0, "seed=$seed: non-reflected methods must be renamed")

      val mapping = Files.readAllLines(requireNotNull(result.mapping))
      fun renamed(original: String) = mapping.first { it.startsWith("$original -> ") }.substringAfter(" -> ").replace('/', '.')
      val workload = renamed("mh/Workload")
      val target = renamed("mh/Target")

      URLClassLoader(arrayOf(output.toUri().toURL()), javaClass.classLoader).use { loader ->
        // 1. The literal findGetter/findVirtual targets still bind through the renamed JAR.
        val roundTrip = loader.loadClass(workload).getMethod("roundTrip", Int::class.javaPrimitiveType)
        for (id in intArrayOf(0, 1, -5, 42, 1000)) {
          assertEquals(
            "v=${id * 3},g=${4 * id + 11}",
            roundTrip.invoke(null, id),
            "seed=$seed id=$id: literal Lookup field/method names must be kept",
          )
        }

        // 2. Selectivity: reflected members keep their names; the others do not.
        val targetClass = loader.loadClass(target)
        assertTrue(targetClass.declaredFields.any { it.name == "value" }, "seed=$seed: findGetter field 'value' kept")
        assertTrue(targetClass.declaredMethods.any { it.name == "greet" }, "seed=$seed: findVirtual method 'greet' kept")
        assertFalse(targetClass.declaredFields.any { it.name == "hidden" }, "seed=$seed: non-reflected field 'hidden' renamed")
        assertFalse(targetClass.declaredMethods.any { it.name == "secret" }, "seed=$seed: non-reflected method 'secret' renamed")
        assertFalse(targetClass.declaredMethods.any { it.name == "dyn" }, "seed=$seed: non-literal-reflected method 'dyn' renamed")

        // 3. Boundary: a non-literal finder name is not kept, so binding the handle now fails.
        val dynamic = loader.loadClass(workload).getMethod("roundTripDynamic", Int::class.javaPrimitiveType)
        val thrown = runCatching { dynamic.invoke(null, 5) }.exceptionOrNull()
        assertTrue(
          thrown is InvocationTargetException && thrown.targetException is NoSuchMethodException,
          "seed=$seed: a dynamically named Lookup target must not be silently kept (was: $thrown)",
        )
      }
    }
  }
}
