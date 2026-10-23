package com.peco2282.obfaryn.core.linkage

import com.peco2282.obfaryn.core.*
import com.peco2282.obfaryn.core.fixtures.JavaJarFixture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PrelinkTest {
  @TempDir lateinit var dir: Path

  @Test fun boundedPreparationDoesNotInitializeOrInvokeTargetsAndIsSafeUnderRaces() {
    val marker = "obfaryn.prelink.target.initialized"
    System.clearProperty(marker)
    val input = JavaJarFixture.compile(dir, mapOf(
      "app/Caller" to """
        package app; public class Caller {
          public static int value(int n) { return Target.work(n) + Math.abs(n); }
        }
      """.trimIndent(),
      "app/Target" to """
        package app; public class Target {
          static { System.setProperty("$marker", "yes"); }
          public static int calls;
          public static int work(int n) { calls++; return n * 3; }
        }
      """.trimIndent(),
    ))
    val output = dir.resolve("prepared.jar")
    Obfuscator(ObfuscationConfig(applicationPackages = listOf("app"), callIndirection = true, prelinkCalls = true,
      stringEncryption = false, numberObfuscation = false, renameClasses = false, renameMethods = false,
      renameFields = false, seed = 9, verifyOutput = true)).obfuscate(input, output)
    URLClassLoader(arrayOf(output.toUri().toURL()), null).use { loader ->
      val caller = loader.loadClass("app.Caller")
      val runtime = loader.loadClass("com.peco2282.obfaryn.runtime.ProtectionRuntime")
      val prepare = runtime.getMethod("prelink", Class::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
      assertEquals(0, prepare.invoke(null, caller, 0, 0))
      assertEquals(1, prepare.invoke(null, caller, 0, 1))
      assertNull(System.getProperty(marker), "Resolving a static target must not initialize it")
      assertEquals(1, prepare.invoke(null, caller, 1, 1))
      assertEquals(0, prepare.invoke(null, caller, 2, 1))
      val executor = Executors.newFixedThreadPool(4)
      try {
        val futures = (1..16).map { executor.submit<Int> { prepare.invoke(null, caller, 0, 10) as Int } }
        futures.forEach { assertEquals(2, it.get(10, TimeUnit.SECONDS)) }
      } finally { executor.shutdownNow() }
      assertNull(System.getProperty(marker))
      assertEquals(8, caller.getMethod("value", Int::class.javaPrimitiveType).invoke(null, 2))
      assertEquals("yes", System.getProperty(marker))
      assertEquals(1, loader.loadClass("app.Target").getField("calls").getInt(null))
    }
    System.clearProperty(marker)
  }
  @Test fun boundedRangesCrossGeneratedChunksWithoutExecutingTargets() {
    val calls = (1..220).joinToString("\n") { "sum += Target.work(n);" }
    val input = JavaJarFixture.compile(dir, mapOf(
      "app/Caller" to "package app; public class Caller { public static int many(int n) { int sum=0; $calls return sum; } }",
      "app/Target" to "package app; public class Target { public static int calls; public static int work(int n) { calls++; return n; } }",
    ))
    val output = dir.resolve("chunks.jar")
    Obfuscator(ObfuscationConfig(applicationPackages = listOf("app"), callIndirection = true, prelinkCalls = true,
      stringEncryption = false, numberObfuscation = false, renameClasses = false, renameMethods = false,
      renameFields = false, seed = 9, verifyOutput = true)).obfuscate(input, output)
    URLClassLoader(arrayOf(output.toUri().toURL()), null).use { loader ->
      val caller = loader.loadClass("app.Caller")
      val target = loader.loadClass("app.Target")
      val prepare = loader.loadClass("com.peco2282.obfaryn.runtime.ProtectionRuntime").getMethod("prelink",
        Class::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
      assertEquals(20, prepare.invoke(null, caller, 90, 20))
      assertEquals(29, prepare.invoke(null, caller, 191, 100))
      assertEquals(0, prepare.invoke(null, caller, 220, 100))
      assertEquals(0, target.getField("calls").getInt(null))
      assertEquals(440, caller.getMethod("many", Int::class.javaPrimitiveType).invoke(null, 2))
      assertEquals(220, target.getField("calls").getInt(null))
    }
  }

  @Test fun prelinkApiIsAvailableEvenIfEveryBodyIsExcluded() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Plain" to
      "package app; @com.peco2282.obfaryn.annotations.NoObfuscation public class Plain { public static int value() { return 3; } }"))
    val output = dir.resolve("plain.jar")
    Obfuscator(ObfuscationConfig(applicationPackages = listOf("app"), callIndirection = true, prelinkCalls = true,
      renameClasses = false, renameMethods = false, renameFields = false, verifyOutput = true)).obfuscate(input, output)
    URLClassLoader(arrayOf(output.toUri().toURL()), null).use { loader ->
      val prepare = loader.loadClass("com.peco2282.obfaryn.runtime.ProtectionRuntime").getMethod("prelink", Class::class.java, Int::class.javaPrimitiveType)
      assertEquals(0, prepare.invoke(null, loader.loadClass("app.Plain"), 10))
    }
  }

}
