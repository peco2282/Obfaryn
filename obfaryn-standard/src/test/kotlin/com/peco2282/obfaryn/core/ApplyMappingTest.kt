package com.peco2282.obfaryn.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.readText

/** Re-applying a separately-obfuscated API's mapping to a later consumer build (apply-mapping). */
class ApplyMappingTest {
  /** api/Greeter with a public greet() -> "hi". Obfuscated on its own and shipped. */
  private fun greeterBytes(): ByteArray {
    val cw = ClassWriter(0)
    cw.visit(V1_8, ACC_PUBLIC, "api/Greeter", null, "java/lang/Object", null)
    cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
      visitCode(); visitVarInsn(ALOAD, 0); visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
      visitInsn(RETURN); visitMaxs(1, 1); visitEnd()
    }
    cw.visitMethod(ACC_PUBLIC, "greet", "()Ljava/lang/String;", null, null).apply {
      visitCode(); visitLdcInsn("hi"); visitInsn(ARETURN); visitMaxs(1, 1); visitEnd()
    }
    cw.visitEnd()
    return cw.toByteArray()
  }

  /** app/Main.run() -> new Greeter().greet(); built later, after the API is already obfuscated. */
  private fun mainBytes(): ByteArray {
    val cw = ClassWriter(0)
    cw.visit(V1_8, ACC_PUBLIC, "app/Main", null, "java/lang/Object", null)
    cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
      visitCode(); visitVarInsn(ALOAD, 0); visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
      visitInsn(RETURN); visitMaxs(1, 1); visitEnd()
    }
    cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "run", "()Ljava/lang/String;", null, null).apply {
      visitCode()
      visitTypeInsn(NEW, "api/Greeter"); visitInsn(DUP)
      visitMethodInsn(INVOKESPECIAL, "api/Greeter", "<init>", "()V", false)
      visitMethodInsn(INVOKEVIRTUAL, "api/Greeter", "greet", "()Ljava/lang/String;", false)
      visitInsn(ARETURN); visitMaxs(2, 0); visitEnd()
    }
    cw.visitEnd()
    return cw.toByteArray()
  }

  private fun jar(path: Path, entries: Map<String, ByteArray>): Path {
    JarOutputStream(Files.newOutputStream(path)).use { out ->
      for ((name, data) in entries) { out.putNextEntry(JarEntry(name)); out.write(data); out.closeEntry() }
    }
    return path
  }

  private fun newName(mapping: String, prefix: String): String =
    mapping.lineSequence().first { it.trim().startsWith(prefix) }.substringAfterLast("-> ").trim()

  private fun minimal(vararg roots: String) = ObfuscationConfig(
    applicationPackages = roots.toList(), stringEncryption = false, numberObfuscation = false,
    verifyOutput = true, seed = 1,
  )

  @Test fun consumerFollowsSeparatelyObfuscatedApi(@TempDir dir: Path) {
    // Phase 1: obfuscate the API on its own and "ship" it.
    val apiOut = dir.resolve("api-out.jar")
    val apiMapping = dir.resolve("api.mapping.txt")
    Obfuscator(minimal("api")).obfuscate(jar(dir.resolve("api.jar"), mapOf("api/Greeter.class" to greeterBytes())), apiOut, apiMapping)
    val apiMapText = apiMapping.readText()
    val greeterObf = newName(apiMapText, "api/Greeter -> ")
    assertNotEquals("api/Greeter", greeterObf) { "the API class was renamed" }

    // Phase 2: obfuscate the consumer later, re-applying the API mapping, with the shipped API on classpath.
    val appOut = dir.resolve("app-out.jar")
    val appMapping = dir.resolve("app.mapping.txt")
    Obfuscator(minimal("app").copy(appliedMappings = listOf(apiMapping), classpath = listOf(apiOut)))
      .obfuscate(jar(dir.resolve("app.jar"), mapOf("app/Main.class" to mainBytes())), appOut, appMapping)
    val appMapText = appMapping.readText()

    // The consumer mapping is self-contained: it carries the API's fixed assignment too (merged output).
    assertTrue(appMapText.lineSequence().any { it == "api/Greeter -> $greeterObf" }) {
      "the consumer mapping merges in the applied API rename:\n$appMapText"
    }

    // Provenance: the API records its own digest, and the consumer accumulates the API's plus its own.
    assertTrue(apiMapText.lineSequence().any { it.startsWith("# source api-out.jar ") }) {
      "the API mapping records its own output digest:\n$apiMapText"
    }
    assertTrue(appMapText.lineSequence().any { it.startsWith("# source api-out.jar ") }) {
      "the consumer mapping carries the applied API's provenance:\n$appMapText"
    }
    assertTrue(appMapText.lineSequence().any { it.startsWith("# source app-out.jar ") }) {
      "the consumer mapping records its own output digest for the next apply in a chain:\n$appMapText"
    }

    // Load the shipped API + the later consumer together (originals are off this classpath) and run it.
    URLClassLoader(arrayOf(apiOut.toUri().toURL(), appOut.toUri().toURL()), null).use { loader ->
      val mainNew = newName(appMapText, "app/Main -> ").replace('/', '.')
      val runNew = newName(appMapText, "app/Main.run()")
      val produced = loader.loadClass(mainNew).getMethod(runNew).invoke(null)
      assertEquals("hi", produced) {
        "the consumer's reference to the separately-obfuscated API resolved to its real renamed name and ran"
      }
    }
  }

  @Test fun stalyAppliedMappingAgainstADifferentBuildIsRejected(@TempDir dir: Path) {
    val apiOut = dir.resolve("api-out.jar")
    val apiMapping = dir.resolve("api.mapping.txt")
    Obfuscator(minimal("api")).obfuscate(jar(dir.resolve("api.jar"), mapOf("api/Greeter.class" to greeterBytes())), apiOut, apiMapping)

    // Ship a different build of the API under the same file name (its digest no longer matches the mapping).
    val staleDir = Files.createDirectories(dir.resolve("stale"))
    val staleApi = staleDir.resolve("api-out.jar")
    Files.copy(apiOut, staleApi)
    Files.write(staleApi, byteArrayOf(0), java.nio.file.StandardOpenOption.APPEND)

    val error = assertThrows(Exception::class.java) {
      Obfuscator(minimal("app").copy(appliedMappings = listOf(apiMapping), classpath = listOf(staleApi)))
        .obfuscate(jar(dir.resolve("app.jar"), mapOf("app/Main.class" to mainBytes())), dir.resolve("app-out.jar"), dir.resolve("app.mapping.txt"))
    }
    assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it.message?.contains("stale") == true }) {
      "a mapping applied against a different build of the artifact is rejected: ${error.message}"
    }
  }

  @Test fun sharedClassBetweenApplyAndInputIsRejected(@TempDir dir: Path) {
    val apiOut = dir.resolve("api2-out.jar")
    val apiMapping = dir.resolve("api2.mapping.txt")
    Obfuscator(minimal("api")).obfuscate(jar(dir.resolve("api2.jar"), mapOf("api/Greeter.class" to greeterBytes())), apiOut, apiMapping)

    // A later build that itself contains api/Greeter must not silently diverge from the applied mapping.
    val clash = jar(dir.resolve("clash.jar"), mapOf("api/Greeter.class" to greeterBytes()))
    val error = assertThrows(Exception::class.java) {
      Obfuscator(minimal("api").copy(appliedMappings = listOf(apiMapping), classpath = listOf(apiOut)))
        .obfuscate(clash, dir.resolve("clash-out.jar"), dir.resolve("clash.mapping.txt"))
    }
    assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it.message?.contains("api/Greeter") == true }) {
      "a class present in both an applied mapping and the input is rejected: ${error.message}"
    }
  }
}
