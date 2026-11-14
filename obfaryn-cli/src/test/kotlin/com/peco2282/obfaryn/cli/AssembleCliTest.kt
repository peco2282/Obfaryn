package com.peco2282.obfaryn.cli

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

class AssembleCliTest {
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

  /** app/Main: run() -> new Greeter().greet(); secret() -> a literal app-side encryption hides. */
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

  private fun jar(path: Path, entries: Map<String, ByteArray>): Path {
    JarOutputStream(Files.newOutputStream(path)).use { out ->
      for ((name, data) in entries) { out.putNextEntry(JarEntry(name)); out.write(data); out.closeEntry() }
    }
    return path
  }

  private fun classNewName(mapping: String, original: String) =
    mapping.lineSequence().first { it.startsWith("$original -> ") }.substringAfterLast("-> ").trim()

  private fun methodNewName(mapping: String, prefix: String) =
    mapping.lineSequence().first { it.trim().startsWith(prefix) }.substringAfterLast("-> ").trim()

  @Test fun assembleKeepsCrossArtifactRenameConsistentAndAppliesPerArtifactBody(@TempDir dir: Path) {
    val apiIn = jar(dir.resolve("api.jar"), mapOf("api/Greeter.class" to greeterBytes()))
    val appIn = jar(dir.resolve("app.jar"), mapOf("app/Main.class" to mainBytes()))
    val apiOut = dir.resolve("api-out.jar")
    val appOut = dir.resolve("app-out.jar")
    val mappingFile = dir.resolve("assembly.mapping.txt")
    // api keeps its literals plaintext; app encrypts its own. Per-artifact body via args files.
    val apiArgs = dir.resolve("api.args").also { it.writeText("--no-string-encryption --no-number-obfuscation --application-package api --verify-output") }
    val appArgs = dir.resolve("app.args").also { it.writeText("--application-package app --no-number-obfuscation --verify-output") }

    val out = StringWriter()
    val err = StringWriter()
    val status = runCli(arrayOf(
      "assemble",
      "-a", "api=$apiIn=$apiOut",
      "-a", "app=$appIn=$appOut",
      "--artifact-args", "api=$apiArgs",
      "--artifact-args", "app=$appArgs",
      "--seed", "7",
      "--mapping", "$mappingFile",
    ), PrintWriter(out), PrintWriter(err))

    assertEquals(0, status) { "assemble failed: $err" }
    assertTrue(Files.isRegularFile(apiOut) && Files.isRegularFile(appOut))
    assertTrue(Files.isRegularFile(mappingFile))
    val mapping = mappingFile.readText()

    // The API class was renamed and the single mapping covers both artifacts.
    assertNotEquals("api/Greeter", classNewName(mapping, "api/Greeter")) { "internal API class must be renamed" }
    assertTrue(mapping.contains("app/Main -> ")) { "one mapping covers every artifact" }

    // Load BOTH outputs together (originals absent) and run the consumer through the renamed API.
    URLClassLoader(arrayOf(apiOut.toUri().toURL(), appOut.toUri().toURL()), null).use { loader ->
      val mainNew = classNewName(mapping, "app/Main").replace('/', '.')
      val runNew = methodNewName(mapping, "app/Main.run()")
      assertEquals("hi", loader.loadClass(mainNew).getMethod(runNew).invoke(null)) {
        "the consumer's cross-artifact reference followed the rename and still runs"
      }
    }

    // Per-artifact body settings: app encrypted its literal, api left its own plaintext.
    fun classBytes(jarPath: Path) = java.util.jar.JarFile(jarPath.toFile()).use { jf ->
      buildString { for (e in jf.entries()) if (e.name.endsWith(".class")) append(String(jf.getInputStream(e).readBytes(), Charsets.ISO_8859_1)) }
    }
    assertTrue(classBytes(apiOut).contains(apiToken)) { "api kept its string literal (encryption off)" }
    assertFalse(classBytes(appOut).contains(appSecret)) { "app encrypted its string literal (encryption on)" }
  }

  @Test fun assembleRejectsDuplicateClassAcrossArtifacts(@TempDir dir: Path) {
    val one = jar(dir.resolve("one.jar"), mapOf("api/Greeter.class" to greeterBytes()))
    val two = jar(dir.resolve("two.jar"), mapOf("api/Greeter.class" to greeterBytes()))
    val err = StringWriter()
    val status = runCli(arrayOf(
      "assemble",
      "-a", "one=$one=${dir.resolve("one-out.jar")}",
      "-a", "two=$two=${dir.resolve("two-out.jar")}",
      "--mapping", "${dir.resolve("m.txt")}",
    ), PrintWriter(StringWriter()), PrintWriter(err))
    assertNotEquals(0, status)
    assertTrue(err.toString().contains("api/Greeter")) { "error names the colliding class: $err" }
  }
}
