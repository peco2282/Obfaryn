package com.peco2282.obfaryn.cli

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.*
import java.net.URLClassLoader
import java.nio.file.*
import java.util.jar.*

class PackageCliTest {
  @TempDir lateinit var dir: Path

  @Test fun `API-only providers work in both invocations without implicitly running installed packages`() {
    val writer = ClassWriter(0)
    writer.visit(52, Opcodes.ACC_PUBLIC, "example/Main", null, "java/lang/Object", null)
    writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "answer", "()I", null, null).apply {
      visitCode(); visitIntInsn(Opcodes.BIPUSH, 7); visitInsn(Opcodes.IRETURN); visitMaxs(1, 0); visitEnd()
    }
    writer.visitEnd()
    val source = dir.resolve("input.jar")
    JarOutputStream(Files.newOutputStream(source)).use { jar ->
      jar.putNextEntry(JarEntry("example/Main.class")); jar.write(writer.toByteArray()); jar.closeEntry()
    }
    for ((index, prefix) in listOf(emptyList(), listOf("obfuscate")).withIndex()) {
      for ((suffix, extra) in listOf("empty" to emptyList(), "custom" to listOf("--package", "example-padding"),
        "platform" to listOf("--also-platform", "example"))) {
        val output = dir.resolve("$index-$suffix.jar")
        val report = dir.resolve("$index-$suffix.json")
        val stdout = StringWriter(); val stderr = StringWriter()
        val args = prefix + listOf(source.toString(), "--no-default-package", "-o", output.toString(),
          "--report", report.toString(), "--verify-output") + extra
        assertEquals(0, runCli(args.toTypedArray(), PrintWriter(stdout), PrintWriter(stderr)), stderr.toString())
        val text = String(Files.readAllBytes(report))
        assertEquals(suffix != "empty", text.contains("example-padding"), text)
        assertFalse(text.contains("string-encryption"), text)
        URLClassLoader(arrayOf(output.toUri().toURL()), null).use { loader ->
          assertEquals(7, loader.loadClass("example.Main").getMethod("answer").invoke(null))
        }
      }
    }
  }

  @Test fun `custom feature identifiers survive CLI conversion`() {
    val rule = FeatureExclusionConverter().convert("example:padding=example/.*")
    assertEquals("example:padding", rule.features.keys.single().name)
  }
}
