package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.protection.ControlFlowMode
import com.peco2282.obfaryn.api.protection.TransformationBudget
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Kotlin `kotlinx.serialization` compatibility (Phase 1/6 follow-up). A `@Serializable` data
 * class's generated `$serializer`/encode/decode bytecode is produced by the Kotlin serialization
 * compiler plugin at compile time -- it calls the data class's constructor and accessors directly
 * in plain bytecode, not through runtime reflection by name -- so renaming is the obfuscation
 * concern that could plausibly break it (unlike reflection-based frameworks, Kotlin metadata
 * stripping is not expected to matter here). This test renames classes/methods/fields through the
 * full `strong` pipeline and keeps only the round-trip entry point's own name, the same pattern
 * [KotlinStatefulDifferentialTest] uses, so the serializer's own generated code is exercised
 * unprotected.
 *
 * The kotlinx.serialization compiler plugin is not yet published for the Kotlin Gradle Plugin
 * version this project's `kotlin("compiler-embeddable")` resolves to (2.4.21; see
 * `obfaryn-standard/build.gradle.kts`), only up to 2.2.0. Compiling the fixture therefore loads an
 * isolated, internally-consistent compiler + plugin pair (2.1.21, matching the project's pinned
 * language/API level) from the `serializationCompiler` Gradle configuration, through its own
 * classloader, entirely separate from this test class's own (2.4.21) Kotlin compiler classes.
 */
class SerializationCompatibilityTest {
  @TempDir lateinit var dir: Path

  private fun isolatedCompilerClasspath(): List<File> {
    val property = System.getProperty("obfaryn.serializationCompilerClasspath")
      ?: error("obfaryn.serializationCompilerClasspath system property not set; run via Gradle")
    return property.split(File.pathSeparator).map(::File)
  }

  private fun compile(source: Path, output: Path) {
    val classpathFiles = isolatedCompilerClasspath()
    val pluginJar = classpathFiles.firstOrNull { it.name.contains("kotlin-serialization-compiler-plugin") }
      ?: error("kotlin-serialization-compiler-plugin-embeddable not found in the serializationCompiler configuration")
    // Isolated from this test class's own (2.4.21) Kotlin compiler classes: a `parent = null`
    // loader resolves JDK classes via the bootstrap loader only, never this module's classpath.
    val loader = URLClassLoader(classpathFiles.map { it.toURI().toURL() }.toTypedArray(), null)
    val compilerClass = loader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
    val compiler = compilerClass.getConstructor().newInstance()
    val execMethod = compilerClass.getMethod("exec", PrintStream::class.java, Array<String>::class.java)
    val diagnostics = ByteArrayOutputStream()
    val classpath = classpathFiles.joinToString(File.pathSeparator) { it.toString() }
    val args = arrayOf("-no-stdlib", "-no-reflect", "-jvm-target", "17", "-Xplugin=${pluginJar}",
      "-classpath", classpath, "-d", output.toString(), source.toString())
    val result = PrintStream(diagnostics).use { execMethod.invoke(compiler, it, args) }
    // `result`'s own class is loaded by the isolated `loader`, but `java.lang.Enum` itself is a
    // single bootstrap-loaded class shared by every classloader, so this cast is safe.
    assertEquals("OK", (result as Enum<*>).name, diagnostics.toString())
  }

  private val source = """
    package serial
    import kotlinx.serialization.Serializable
    import kotlinx.serialization.encodeToString
    import kotlinx.serialization.decodeFromString
    import kotlinx.serialization.json.Json

    @Serializable
    data class Payload(val id: Int, val name: String, val tags: List<String>, val active: Boolean)

    object Workload {
      fun roundTrip(id: Int): String {
        val original = Payload(id, "item-${'$'}id", listOf("a", "b", "c"), id % 2 == 0)
        val json = Json.encodeToString(original)
        val decoded = Json.decodeFromString<Payload>(json)
        return (decoded == original).toString() + ":" + decoded.toString() + ":" + json.length
      }
    }
  """.trimIndent()

  @Test fun generatedSerializerSurvivesFullRenamingAndMetadataHandling() {
    val input = dir.resolve("serial.jar")
    val sourceFile = dir.resolve("Workload.kt")
    Files.write(sourceFile, source.toByteArray())
    val classesDir = dir.resolve("classes")
    compile(sourceFile, classesDir)
    java.util.jar.JarOutputStream(Files.newOutputStream(input)).use { jar ->
      Files.walk(classesDir).use { paths ->
        paths.filter { it.toString().endsWith(".class") }.sorted().forEach { path ->
          jar.putNextEntry(java.util.jar.JarEntry(classesDir.relativize(path).toString().replace('\\', '/')))
          jar.write(Files.readAllBytes(path)); jar.closeEntry()
        }
      }
    }
    val classpathFiles = isolatedCompilerClasspath()
    val classpathUrls = classpathFiles.map { it.toURI().toURL() }.toTypedArray()
    for (seed in listOf(0L, 1L, 20261110L, Long.MIN_VALUE)) {
      val output = dir.resolve("serial-$seed.jar")
      val config = ObfuscationConfig(
        applicationPackages = listOf("serial"), seed = seed,
        protectionLevel = ProtectionLevel.STRONG, controlFlowMode = ControlFlowMode.LIGHT,
        stringEncryption = true, numberObfuscation = true,
        integerArithmetic = true, maxArithmeticOperationsPerMethod = 4,
        callIndirection = true, prelinkCalls = true,
        renameClasses = true, renameMethods = true, renameFields = true,
        flattenPackages = true, targetPackage = "hidden", stripGenericSignatures = true,
        keep = listOf("serial.Workload#roundTrip(I)Ljava/lang/String;"),
        verifyOutput = true, excludeVirtualization = listOf(".*"),
        transformationBudget = TransformationBudget(
          maxInputInstructions = 10000, maxInputBranches = 256, maxAddedInstructions = 4096, maxGrowthPercent = 400),
        classpath = classpathFiles.map { it.toPath() },
      )
      val result = Obfuscator(config).obfuscate(input, output)
      assertTrue(result.classesRenamed > 0, "seed=$seed: the serializable class/companion must actually be renamed for this test to mean anything")
      val mapped = Files.readAllLines(requireNotNull(result.mapping))
        .first { it.startsWith("serial/Workload -> ") }.substringAfter(" -> ").replace('/', '.')

      URLClassLoader(arrayOf(input.toUri().toURL(), *classpathUrls), null).use { originalLoader ->
        URLClassLoader(arrayOf(output.toUri().toURL(), *classpathUrls), null).use { obfuscatedLoader ->
          // `object Workload` compiles `roundTrip` as an instance method on the Kotlin singleton,
          // reached through the generated static `INSTANCE` field, not as a JVM static method.
          val originalType = originalLoader.loadClass("serial.Workload")
          val obfuscatedType = obfuscatedLoader.loadClass(mapped)
          val originalInstance = originalType.getField("INSTANCE").get(null)
          val obfuscatedInstance = obfuscatedType.getField("INSTANCE").get(null)
          val originalMethod = originalType.getMethod("roundTrip", Int::class.javaPrimitiveType)
          val obfuscatedMethod = obfuscatedType.getMethod("roundTrip", Int::class.javaPrimitiveType)
          for (id in intArrayOf(0, 1, -5, 42, 1000)) {
            val a = originalMethod.invoke(originalInstance, id)
            val b = obfuscatedMethod.invoke(obfuscatedInstance, id)
            assertEquals(a, b, "seed=$seed id=$id")
            assertTrue((a as String).startsWith("true:"), "round trip must decode back to an equal value: $a")
          }
        }
      }
    }
  }
}
