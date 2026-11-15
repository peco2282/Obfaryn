package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.standard.AssemblyModule
import com.peco2282.obfaryn.standard.AssemblySession
import com.peco2282.obfaryn.standard.RecordingPackageState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class AssemblySessionTest {
  private val apiToken = "APIKEY-PLAINTEXT-VALUE"
  private val appSecret = "SECRET-TOKEN-VALUE-XYZ"

  /** api/Greeter: greet() -> "hi", apiToken() -> a plaintext literal. */
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
    cw.visitMethod(ACC_PUBLIC, "apiToken", "()Ljava/lang/String;", null, null).apply {
      visitCode(); visitLdcInsn(apiToken); visitInsn(ARETURN); visitMaxs(1, 1); visitEnd()
    }
    cw.visitEnd()
    return cw.toByteArray()
  }

  /** app/Main: run() -> new Greeter().greet(); secret() -> a literal that app-side encryption hides. */
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
    cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "secret", "()Ljava/lang/String;", null, null).apply {
      visitCode(); visitLdcInsn(appSecret); visitInsn(ARETURN); visitMaxs(1, 0); visitEnd()
    }
    cw.visitEnd()
    return cw.toByteArray()
  }

  /** app/Holder: secret() -> a plaintext literal, with no external references (verifies standalone). */
  private fun holderBytes(): ByteArray {
    val cw = ClassWriter(0)
    cw.visit(V1_8, ACC_PUBLIC, "app/Holder", null, "java/lang/Object", null)
    cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
      visitCode(); visitVarInsn(ALOAD, 0); visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
      visitInsn(RETURN); visitMaxs(1, 1); visitEnd()
    }
    cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "secret", "()Ljava/lang/String;", null, null).apply {
      visitCode(); visitLdcInsn(appSecret); visitInsn(ARETURN); visitMaxs(1, 0); visitEnd()
    }
    cw.visitEnd()
    return cw.toByteArray()
  }

  private fun jar(path: Path, entries: Map<String, ByteArray>): Path {
    JarOutputStream(Files.newOutputStream(path)).use { out ->
      for ((name, data) in entries) {
        out.putNextEntry(JarEntry(name)); out.write(data); out.closeEntry()
      }
    }
    return path
  }

  private fun decompressedClasses(jarPath: Path): String = JarFile(jarPath.toFile()).use { jar ->
    buildString {
      for (entry in jar.entries()) if (entry.name.endsWith(".class")) {
        append(String(jar.getInputStream(entry).readBytes(), Charsets.ISO_8859_1))
      }
    }
  }

  private fun classNewName(mapping: String, original: String): String =
    mapping.lineSequence().first { it.startsWith("$original -> ") }.substringAfterLast("-> ").trim()

  private fun methodNewName(mapping: String, prefix: String): String =
    mapping.lineSequence().first { it.trim().startsWith(prefix) }.substringAfterLast("-> ").trim()

  @Test fun apiRenameIsFollowedByConsumerAndPerArtifactBodySettingsApply(@TempDir dir: Path) {
    val apiIn = jar(dir.resolve("api-in.jar"), mapOf("api/Greeter.class" to greeterBytes()))
    val appIn = jar(dir.resolve("app-in.jar"), mapOf("app/Main.class" to mainBytes()))
    val apiOut = dir.resolve("api-out.jar")
    val appOut = dir.resolve("app-out.jar")

    val result = AssemblySession(
      modules = listOf(
        // api keeps its literals plaintext; app encrypts its own.
        AssemblyModule("api", apiIn, apiOut, ObfuscationConfig(
          applicationPackages = listOf("api"), stringEncryption = false, numberObfuscation = false,
          verifyOutput = true, seed = 1)),
        AssemblyModule("app", appIn, appOut, ObfuscationConfig(
          applicationPackages = listOf("app"), stringEncryption = true, numberObfuscation = false,
          verifyOutput = true, seed = 2)),
      ),
      renameConfig = ObfuscationConfig(
        renameClasses = true, renameMethods = true, renameFields = true, seed = 7),
    ).run()

    assertEquals(setOf("api", "app"), result.outputs.keys)
    assertTrue(Files.isRegularFile(apiOut) && Files.isRegularFile(appOut))

    // The API class was actually renamed, and the single mapping covers both artifacts.
    val greeterNew = classNewName(result.mapping, "api/Greeter")
    assertNotEquals("api/Greeter", greeterNew) { "the internal API class must be renamed" }
    assertTrue(result.mapping.contains("app/Main -> ")) { "the one mapping covers every artifact" }

    // Load BOTH output JARs together (originals are not on this classpath) and run the consumer.
    // Parent = bootstrap only, so the original (pre-obfuscation) classes on the test classpath are
    // invisible; the consumer must resolve the API through the renamed sibling JAR.
    URLClassLoader(arrayOf(apiOut.toUri().toURL(), appOut.toUri().toURL()), null).use { loader ->
      val mainNew = classNewName(result.mapping, "app/Main").replace('/', '.')
      val runNew = methodNewName(result.mapping, "app/Main.run()")
      val produced = loader.loadClass(mainNew).getMethod(runNew).invoke(null)
      assertEquals("hi", produced) { "the consumer's cross-artifact reference followed the rename and still runs" }
    }

    // Per-artifact body settings: app encrypted its literal, api left its own plaintext.
    // Scan the DECOMPRESSED class bytes (the JAR itself is DEFLATE-compressed).
    assertTrue(decompressedClasses(apiOut).contains(apiToken)) {
      "api kept its string literal (encryption off for that artifact)"
    }
    assertFalse(decompressedClasses(appOut).contains(appSecret)) {
      "app encrypted its string literal (encryption on for that artifact)"
    }
  }

  @Test fun perArtifactPackageSelectionAndAfterWriteApply(@TempDir dir: Path) {
    RecordingPackageState.reset()
    val appIn = jar(dir.resolve("app-in.jar"), mapOf("app/Holder.class" to holderBytes()))

    // Baseline: the standard recipe runs, encrypting the literal and recording body passes.
    val appOut = dir.resolve("app-out.jar")
    val baseline = AssemblySession(
      listOf(AssemblyModule("app", appIn, appOut, ObfuscationConfig(applicationPackages = listOf("app"), seed = 3))),
      ObfuscationConfig(renameClasses = true, renameMethods = true, renameFields = true, seed = 7),
    ).run()
    assertFalse(decompressedClasses(appOut).contains(appSecret)) { "the standard recipe encrypted the literal" }
    assertTrue(baseline.reports.getValue("app").isNotEmpty()) { "the standard recipe ran body passes" }
    assertTrue(RecordingPackageState.afterWrites.isEmpty()) { "the recorder was not selected for the baseline" }

    // Opt out of the standard recipe and select only the provider-supplied recorder: no body passes
    // run (literal stays plaintext), and the recorder's afterWrite sees the finished output JAR.
    val appOut2 = dir.resolve("app-out2.jar")
    val selected = AssemblySession(
      listOf(AssemblyModule("app", appIn, appOut2, ObfuscationConfig(applicationPackages = listOf("app"), seed = 3),
        packageIds = listOf("test-recorder"), noDefaultPackage = true)),
      ObfuscationConfig(renameClasses = false, renameMethods = false, renameFields = false, seed = 7),
    ).run()
    assertTrue(decompressedClasses(appOut2).contains(appSecret)) { "no standard recipe -> the literal is left plaintext" }
    assertTrue(selected.reports.getValue("app").isEmpty()) { "--no-default-package registered no body passes" }
    assertEquals(listOf(appOut2), RecordingPackageState.afterWrites) { "afterWrite ran once on the finished output" }
  }

  @Test fun protectInputRejectsOverwritingAnArtifactOutput(@TempDir dir: Path) {
    RecordingPackageState.reset()
    val appIn = jar(dir.resolve("app-in.jar"), mapOf("app/Holder.class" to holderBytes()))
    val appOut = dir.resolve("app-out.jar")
    // The selected package declares a protected input that collides with an artifact's output.
    RecordingPackageState.protectInputPath = appOut
    val failure = assertThrows(IllegalArgumentException::class.java) {
      AssemblySession(listOf(AssemblyModule("app", appIn, appOut,
        ObfuscationConfig(applicationPackages = listOf("app")),
        packageIds = listOf("test-recorder"), noDefaultPackage = true))).run()
    }
    assertTrue(failure.message!!.contains("must not be overwritten")) { "protectInput guarded the output destination" }
    RecordingPackageState.reset()
  }

  @Test fun duplicateClassAcrossArtifactsIsRejected(@TempDir dir: Path) {
    val one = jar(dir.resolve("one.jar"), mapOf("api/Greeter.class" to greeterBytes()))
    val two = jar(dir.resolve("two.jar"), mapOf("api/Greeter.class" to greeterBytes()))
    val failure = assertThrows(IllegalArgumentException::class.java) {
      AssemblySession(listOf(
        AssemblyModule("one", one, dir.resolve("one-out.jar")),
        AssemblyModule("two", two, dir.resolve("two-out.jar")),
      )).run()
    }
    assertTrue(failure.message!!.contains("api/Greeter"))
  }
}
