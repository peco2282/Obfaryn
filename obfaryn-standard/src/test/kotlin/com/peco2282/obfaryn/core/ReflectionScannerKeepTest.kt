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
 * Where [GsonDtoCompatibilityTest]/[JacksonDtoCompatibilityTest] cover a *library* that reads
 * annotations, this covers Obfaryn's own `ReflectionScanner`: a member reached by a constant-string
 * `Class.getDeclaredField`/`getDeclaredMethod` is kept automatically, so literal reflection still
 * resolves through the fully renamed JAR, while unrelated members are still renamed. It also pins
 * the documented boundary -- a name that is not a single string literal (here `new String(...)`) is
 * not kept, so that reflection breaks and needs an explicit keep rule.
 */
class ReflectionScannerKeepTest {
  @TempDir lateinit var dir: Path

  private val sources = mapOf(
    "refl/Bean" to """
      package refl;
      public final class Bean {
        public int secret;   // reached by literal reflection -> kept
        int other;           // never reflected -> renamed
        int token;           // reached only by a non-literal name -> renamed

        public Bean(int id) { this.secret = id * 7; this.other = id - 1; this.token = id + 100; }
        public int compute(int x) { return x + helper() + other; }  // compute reflected -> kept
        int helper() { return 3; }                                  // helper renamed
      }
    """.trimIndent(),
    "refl/Workload" to """
      package refl;
      import java.lang.reflect.Field;
      import java.lang.reflect.Method;
      public final class Workload {
        public static String roundTrip(int id) throws Exception {
          Bean b = new Bean(id);
          Field f = Bean.class.getDeclaredField("secret");
          int s = f.getInt(b);
          Method m = Bean.class.getDeclaredMethod("compute", int.class);
          int c = (int) m.invoke(b, id);
          return "s=" + s + ",c=" + c;
        }
        public static String roundTripDynamic(int id) throws Exception {
          Bean b = new Bean(id);
          // Not a single constant-string argument, so the scanner cannot keep `token`.
          Field f = Bean.class.getDeclaredField(new String("token"));
          return String.valueOf(f.getInt(b));
        }
      }
    """.trimIndent(),
  )

  @Test fun literalReflectionTargetsAreKeptWhileOtherMembersAreRenamed() {
    val input = JavaJarFixture.compile(dir, sources)

    for (seed in listOf(0L, 1L, 20261118L, Long.MIN_VALUE)) {
      val output = dir.resolve("refl-$seed.jar")
      val result = Obfuscator(
        ObfuscationConfig(
          applicationPackages = listOf("refl"),
          seed = seed,
          protectionLevel = ProtectionLevel.STRONG,
          controlFlowMode = ControlFlowMode.LIGHT,
          renameClasses = true,
          renameMethods = true,
          renameFields = true,
          flattenPackages = true,
          targetPackage = "hidden",
          keep = listOf(
            "refl.Workload#roundTrip(I)Ljava/lang/String;",
            "refl.Workload#roundTripDynamic(I)Ljava/lang/String;",
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
      val workload = renamed("refl/Workload")
      val bean = renamed("refl/Bean")

      val classpath = arrayOf(output.toUri().toURL())
      URLClassLoader(classpath, javaClass.classLoader).use { loader ->
        // 1. Literal reflection still resolves through the renamed JAR (scanner kept the members).
        val roundTrip = loader.loadClass(workload).getMethod("roundTrip", Int::class.javaPrimitiveType)
        for (id in intArrayOf(0, 1, -5, 42, 1000)) {
          assertEquals(
            "s=${id * 7},c=${2 * id + 2}",
            roundTrip.invoke(null, id),
            "seed=$seed id=$id: literal-reflected field/method names must be kept",
          )
        }

        // 2. Selectivity: the reflected members keep their names; the others do not survive.
        val beanClass = loader.loadClass(bean)
        assertTrue(beanClass.declaredFields.any { it.name == "secret" }, "seed=$seed: reflected field 'secret' kept")
        assertTrue(beanClass.declaredMethods.any { it.name == "compute" }, "seed=$seed: reflected method 'compute' kept")
        assertFalse(beanClass.declaredFields.any { it.name == "other" }, "seed=$seed: non-reflected field 'other' renamed")
        assertFalse(beanClass.declaredFields.any { it.name == "token" }, "seed=$seed: non-literal-reflected field 'token' renamed")
        assertFalse(beanClass.declaredMethods.any { it.name == "helper" }, "seed=$seed: non-reflected method 'helper' renamed")

        // 3. Boundary: a non-literal reflective name is not kept, so that lookup now fails.
        val dynamic = loader.loadClass(workload).getMethod("roundTripDynamic", Int::class.javaPrimitiveType)
        val thrown = runCatching { dynamic.invoke(null, 5) }.exceptionOrNull()
        assertTrue(
          thrown is InvocationTargetException && thrown.targetException is NoSuchFieldException,
          "seed=$seed: a dynamically named reflective target must not be silently kept (was: $thrown)",
        )
      }
    }
  }
}
