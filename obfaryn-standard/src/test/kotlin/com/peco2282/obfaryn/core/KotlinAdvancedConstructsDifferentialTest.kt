package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.protection.ControlFlowMode
import com.peco2282.obfaryn.api.protection.TransformationBudget
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Differential coverage for Kotlin constructs the other stateful fixtures do not exercise: sealed
 * classes with an exhaustive `when`, `object` declarations (JVM singletons), companion-object
 * factories and shared state, inline functions with non-local returns, extension functions with
 * default/named arguments used out of declaration order, and `enum.entries`.
 *
 * Shares the `fixture.semantics` package/jar strategy used by [KotlinStatefulDifferentialTest]:
 * the fixture is compiled as ordinary test sources, then its `.class` files are read back off the
 * test classpath and packaged for the engine, so no embedded compiler invocation is needed here.
 */
class KotlinAdvancedConstructsDifferentialTest {
  @TempDir lateinit var dir: Path

  @Test fun sealedClassesSingletonsCompanionsAndInlineExtensionsArePreserved() {
    val input = dir.resolve("kotlin-advanced.jar")
    val classes = java.nio.file.Paths.get(fixture.semantics.Workshop::class.java.protectionDomain.codeSource.location.toURI())
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      Files.walk(classes.resolve("fixture/semantics")).use { paths ->
        paths.filter { it.toString().endsWith(".class") }.sorted().forEach { path ->
          jar.putNextEntry(JarEntry(classes.relativize(path).toString()))
          jar.write(Files.readAllBytes(path)); jar.closeEntry()
        }
      }
    }
    val stdlib = kotlin.Unit::class.java.protectionDomain.codeSource.location
    for (seed in listOf(0L, 1L, 20261022L, Long.MIN_VALUE)) {
      val output = dir.resolve("kotlin-advanced-$seed.jar")
      val result = Obfuscator(ObfuscationConfig(
        applicationPackages = listOf("fixture.semantics"), seed = seed,
        protectionLevel = ProtectionLevel.STRONG, controlFlowMode = ControlFlowMode.LIGHT,
        integerArithmetic = true, maxArithmeticOperationsPerMethod = 4, callIndirection = true, prelinkCalls = true,
        flattenPackages = true, targetPackage = "hidden", stripGenericSignatures = true, verifyOutput = true,
        keep = listOf("fixture.semantics.Workshop#run(II)Ljava/lang/String;"),
        excludeVirtualization = listOf(".*"),
        transformationBudget = TransformationBudget(10000, 256, 1024, 300, 60000),
        classpath = listOf(java.nio.file.Paths.get(stdlib.toURI())),
      )).obfuscate(input, output)
      assertTrue(result.classesRenamed > 0 && result.methodsRenamed > 0 && result.fieldsRenamed > 0)
      val mapped = Files.readAllLines(requireNotNull(result.mapping))
        .single { it.startsWith("fixture/semantics/Workshop -> ") }.substringAfter(" -> ").replace('/', '.')

      URLClassLoader(arrayOf(input.toUri().toURL(), stdlib), null).use { left ->
        URLClassLoader(arrayOf(output.toUri().toURL(), stdlib), null).use { right ->
          fun workload(loader: URLClassLoader, name: String): Workload {
            val type = loader.loadClass(name)
            val instance = type.getConstructor(String::class.java).newInstance("session-$seed")
            return Workload(type, instance)
          }
          val a = workload(left, "fixture.semantics.Workshop")
          val b = workload(right, mapped)

          fun differential(op: Int, arg: Int) {
            val before = a.run(op, arg)
            assertEquals(before, b.run(op, arg), "seed=$seed op=$op arg=$arg")
          }

          val args = intArrayOf(Int.MIN_VALUE, -5, -1, 0, 1, 2, 3, 4, 5, 1000, Int.MAX_VALUE)
          for (op in 0..8) for (arg in args) differential(op, arg)

          // Independent oracles, spot-checked at fixed arguments.
          assertEquals(listOf("return", "4"), a.run(0, 4))     // [1,3,5,4] -> first even is 4
          assertEquals(listOf("return", "4"), b.run(0, 4))
          assertEquals(listOf("return", "-1"), a.run(0, 7))    // [1,3,5,7] has no even element -> default
          assertEquals(listOf("return", "-1"), b.run(0, 7))
          assertEquals(listOf("return", "15"), a.run(1, 4))    // 4*2+7
          assertEquals(listOf("return", "15"), b.run(1, 4))
          assertEquals(listOf("return", "clicked:9"), a.run(3, 9))
          assertEquals(listOf("return", "reset"), a.run(5, 0))
          assertEquals(listOf("return", "[session-$seed]"), a.run(6, 0))
          assertEquals(listOf("return", "[session-$seed]"), b.run(6, 0))
          assertEquals(listOf("return", "READY,RUNNING,ENDED:3"), a.run(8, 0))
          assertEquals(listOf("return", "READY,RUNNING,ENDED:3"), b.run(8, 0))

          // Companion-object state is per-classloader; each side's own creation sequence converges
          // identically because both run the same op/arg sequence in the same order.
          differential(9, 0)
          differential(10, 0)
          differential(9, 0)
        }
      }
    }
  }

  private class Workload(private val type: Class<*>, private val instance: Any) {
    private val runMethod = type.getMethod("run", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    fun run(op: Int, arg: Int): Any = try {
      listOf("return", runMethod.invoke(instance, op, arg))
    } catch (e: InvocationTargetException) {
      listOf("throw", e.targetException.javaClass.name, e.targetException.message)
    }
  }
}
