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
 * Differential coverage for Kotlin property delegation and a type-safe DSL builder, which the other
 * `fixture.semantics` fixtures do not exercise: `by lazy` (synchronized init holder), `observable`
 * and `vetoable` (change callbacks), a custom `getValue`/`setValue` operator delegate bound through
 * `KProperty`, map-backed delegated properties, and a `@DslMarker` receiver-lambda builder.
 *
 * Mirrors [KotlinAdvancedConstructsDifferentialTest]: the fixture is compiled as ordinary test
 * sources, its `.class` files are read back off the test classpath and packaged for the engine, then
 * the original and the obfuscated output are run through the same `run(op, arg)` sequence and must
 * agree for every op/arg under several seeds.
 */
class KotlinDelegationAndDslDifferentialTest {
  @TempDir lateinit var dir: Path

  @Test fun delegatedPropertiesAndDslBuilderSurviveObfuscation() {
    val input = dir.resolve("kotlin-delegation.jar")
    val classes = java.nio.file.Paths.get(fixture.semantics.DelegationWorkshop::class.java.protectionDomain.codeSource.location.toURI())
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      Files.walk(classes.resolve("fixture/semantics")).use { paths ->
        paths.filter { it.toString().endsWith(".class") }.sorted().forEach { path ->
          jar.putNextEntry(JarEntry(classes.relativize(path).toString()))
          jar.write(Files.readAllBytes(path)); jar.closeEntry()
        }
      }
    }
    val stdlib = Unit::class.java.protectionDomain.codeSource.location
    for (seed in listOf(0L, 1L, 20261115L, Long.MIN_VALUE)) {
      val output = dir.resolve("kotlin-delegation-$seed.jar")
      val result = Obfuscator(ObfuscationConfig(
        applicationPackages = listOf("fixture.semantics"), seed = seed,
        protectionLevel = ProtectionLevel.STRONG, controlFlowMode = ControlFlowMode.LIGHT,
        integerArithmetic = true, maxArithmeticOperationsPerMethod = 4, callIndirection = true, prelinkCalls = true,
        flattenPackages = true, targetPackage = "hidden", stripGenericSignatures = true, verifyOutput = true,
        keep = listOf("fixture.semantics.DelegationWorkshop#run(II)Ljava/lang/String;"),
        excludeVirtualization = listOf(".*"),
        transformationBudget = TransformationBudget(10000, 256, 1024, 300, 60000),
        classpath = listOf(java.nio.file.Paths.get(stdlib.toURI())),
      )).obfuscate(input, output)
      assertTrue(result.classesRenamed > 0 && result.methodsRenamed > 0 && result.fieldsRenamed > 0)
      val mapped = Files.readAllLines(requireNotNull(result.mapping))
        .single { it.startsWith("fixture/semantics/DelegationWorkshop -> ") }.substringAfter(" -> ").replace('/', '.')

      URLClassLoader(arrayOf(input.toUri().toURL(), stdlib), null).use { left ->
        URLClassLoader(arrayOf(output.toUri().toURL(), stdlib), null).use { right ->
          fun workload(loader: URLClassLoader, name: String): Workload {
            val type = loader.loadClass(name)
            val instance = type.getConstructor(String::class.java).newInstance("session-$seed")
            return Workload(type, instance)
          }
          val a = workload(left, "fixture.semantics.DelegationWorkshop")
          val b = workload(right, mapped)

          fun differential(op: Int, arg: Int) {
            val before = a.run(op, arg)
            assertEquals(before, b.run(op, arg), "seed=$seed op=$op arg=$arg")
          }

          val args = intArrayOf(Int.MIN_VALUE, -7, -1, 0, 1, 2, 5, 42, Int.MAX_VALUE)
          for (op in 0..6) for (arg in args) differential(op, arg)

          // Independent oracles that do not depend on the session string.
          assertEquals(listOf("return", "a;[s:b;5;]"), a.run(5, 5))   // DSL: line + nested section
          assertEquals(listOf("return", "a;[s:b;5;]"), b.run(5, 5))
          assertEquals(listOf("return", "84"), a.run(3, 42))          // custom delegate doubles
          assertEquals(listOf("return", "84"), b.run(3, 42))
          assertEquals(listOf("return", "session-$seed/3"), a.run(4, 0)) // map-backed name/level
          assertEquals(listOf("return", "session-$seed/3"), b.run(4, 0))
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
