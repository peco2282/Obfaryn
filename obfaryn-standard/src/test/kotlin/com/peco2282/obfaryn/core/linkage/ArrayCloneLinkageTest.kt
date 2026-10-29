package com.peco2282.obfaryn.core.linkage

import com.peco2282.obfaryn.core.*
import com.peco2282.obfaryn.core.fixtures.JavaJarFixture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.*
import org.objectweb.asm.tree.*
import java.net.URLClassLoader
import java.nio.file.*
import java.util.jar.*

class ArrayCloneLinkageTest {
  @TempDir lateinit var dir: Path

  @Test fun kotlinStyleEnumArrayCloneKeepsArrayReceiverSemantics() {
    val compiled = JavaJarFixture.compile(dir, mapOf("app/Mode" to "package app; public enum Mode { A, B }"))
    val input = dir.resolve("kotlin-style-enum.jar")
    JarFile(compiled.toFile()).use { source -> JarOutputStream(Files.newOutputStream(input)).use { target ->
      for (entry in source.entries()) {
        val node = ClassNode()
        ClassReader(source.getInputStream(entry)).accept(node, 0)
        for (method in node.methods) for (call in method.instructions.filterIsInstance<MethodInsnNode>()) {
          // Kotlin emits Object.clone for this array, rather than javac's array-owner clone.
          if (call.name == "clone") call.owner = "java/lang/Object"
        }
        target.putNextEntry(JarEntry(entry.name)); target.write(ClassWriter(0).also { node.accept(it) }.toByteArray()); target.closeEntry()
      }
    } }
    for (prepared in listOf(false, true)) {
      val output = dir.resolve("enum-$prepared.jar")
      Obfuscator(ObfuscationConfig(callIndirection = true, prelinkCalls = prepared,
        applicationPackages = listOf("app"), renameClasses = false, renameMethods = false,
        renameFields = false, numberObfuscation = false, stringEncryption = false, verifyOutput = true))
        .obfuscate(input, output)
      URLClassLoader(arrayOf(output.toUri().toURL()), null).use { loader ->
        val cls = loader.loadClass("app.Mode")
        val values = cls.getMethod("values").invoke(null) as Array<*>
        assertEquals(listOf("A", "B"), values.map(Any?::toString))
        assertNotSame(values, cls.getMethod("values").invoke(null))
      }
    }
  }
}
