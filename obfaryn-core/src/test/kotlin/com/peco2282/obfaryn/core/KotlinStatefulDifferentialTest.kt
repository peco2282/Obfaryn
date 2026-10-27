package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.protection.ControlFlowMode
import com.peco2282.obfaryn.api.protection.TransformationBudget
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.InvocationTargetException
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class KotlinStatefulDifferentialTest {
  @TempDir lateinit var dir: Path

  @Test fun combinedPassesKeepDefaultsValueClassesDataClassesEnumsLambdasAndCallableReferences() {
    val input = dir.resolve("kotlin.jar")
    val classes = java.nio.file.Paths.get(fixture.semantics.Session::class.java.protectionDomain.codeSource.location.toURI())
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
      val output = dir.resolve("kotlin-$seed.jar")
      val result = Obfuscator(ObfuscationConfig(applicationPackages = listOf("fixture.semantics"), seed = seed,
        protectionLevel = ProtectionLevel.STRONG, controlFlowMode = ControlFlowMode.LIGHT,
        integerArithmetic = true, maxArithmeticOperationsPerMethod = 4, callIndirection = true, prelinkCalls = true,
        flattenPackages = true, targetPackage = "hidden", stripGenericSignatures = true, verifyOutput = true,
        keep = listOf("fixture.semantics.Session#apply(II)Ljava/lang/String;", "fixture.semantics.Session#snapshot()Ljava/lang/String;"),
        transformationBudget = TransformationBudget(10000, 256, 1024, 300, 60000),
        classpath = listOf(java.nio.file.Paths.get(stdlib.toURI())))).obfuscate(input, output)
      assertTrue(result.classesRenamed > 0 && result.methodsRenamed > 0 && result.fieldsRenamed > 0)
      val mapped = Files.readAllLines(requireNotNull(result.mapping)).single { it.startsWith("fixture/semantics/Session -> ") }.substringAfter(" -> ").replace('/', '.')
      URLClassLoader(arrayOf(input.toUri().toURL(), stdlib), null).use { left ->
        URLClassLoader(arrayOf(output.toUri().toURL(), stdlib), null).use { right ->
          val a = left.loadClass("fixture.semantics.Session").getConstructor().newInstance()
          val b = right.loadClass(mapped).getConstructor().newInstance()
          fun snapshot(o: Any) = o.javaClass.getMethod("snapshot").invoke(o)
          fun apply(o: Any, op: Int, value: Int): Any? = try {
            o.javaClass.getMethod("apply", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(o, op, value)
          } catch (e: InvocationTargetException) { listOf(e.targetException.javaClass.name, e.targetException.message) }
          assertEquals("READY:0:0:0:ready", snapshot(a))
          assertEquals(snapshot(a), snapshot(b))
          val steps = listOf(0 to 0, 4 to 0, 1 to -1, 1 to 0, 2 to 0, 3 to 2, 1 to 1)
          val expected = listOf("RUNNING:0:0:0:ready", "RUNNING:0:0:0:ready", "RUNNING:0:0:1:ready",
            "RUNNING:0:2:3:ready", "RUNNING:1:4:5:ready", "ENDED:3:6:7:ready", "ENDED:3:6:7:ready")
          for ((index, step) in steps.withIndex()) {
            assertEquals(apply(a, step.first, step.second), apply(b, step.first, step.second), "seed=$seed step=$index")
            assertEquals(expected[index], snapshot(a))
            assertEquals(snapshot(a), snapshot(b))
          }
        }
      }
    }
  }
}
