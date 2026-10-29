package com.peco2282.obfaryn.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class IntegrityTest {
  @TempDir lateinit var dir: Path

  private fun config(): ObfuscationConfig {
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val secret = dir.resolve("private.der")
    val public = dir.resolve("public.der")
    Files.write(secret, pair.private.encoded)
    Files.write(public, pair.public.encoded)
    return ObfuscationConfig(integrityPrivateKey = secret, integrityPublicKey = public,
      renameClasses = false, renameMethods = false, renameFields = false,
      stringEncryption = false, numberObfuscation = false, seed = 42)
  }

  private fun input(): Path {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Sample", null, "java/lang/Object", null)
    writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "answer", "()I", null, null).apply {
      visitCode(); visitIntInsn(Opcodes.BIPUSH, 42); visitInsn(Opcodes.IRETURN); visitMaxs(1, 0); visitEnd()
    }
    writer.visitEnd()
    val input = dir.resolve("input.jar")
    JarOutputStream(Files.newOutputStream(input)).use { out ->
      for ((name, bytes) in listOf("Sample.class" to writer.toByteArray(), "data.txt" to byteArrayOf(1, 2, 3))) {
        out.putNextEntry(JarEntry(name)); out.write(bytes); out.closeEntry()
      }
    }
    return input
  }

  private fun execute(jar: Path): Int = URLClassLoader(arrayOf(jar.toUri().toURL()), platformClassLoader()).use {
    it.loadClass("Sample").getMethod("answer").invoke(null) as Int
  }

  private fun rewrite(source: Path, name: String, edit: (MutableMap<String, ByteArray>) -> Unit): Path {
    val entries = JarFile(source.toFile()).use { jar ->
      jar.entries().toList().associate { it.name to jar.getInputStream(it).use { stream -> stream.readBytes() } }.toMutableMap()
    }
    edit(entries)
    val output = dir.resolve(name)
    JarOutputStream(Files.newOutputStream(output)).use { out ->
      for ((path, bytes) in entries) { out.putNextEntry(JarEntry(path)); out.write(bytes); out.closeEntry() }
    }
    return output
  }

  @Test fun verifiesOfflineAndRejectsTampering() {
    val output = dir.resolve("signed.jar")
    val config = config()
    val input = input()
    Obfuscator(config).obfuscate(input, output)
    assertEquals(42, execute(output))
    val repeated = dir.resolve("repeated.jar")
    Obfuscator(config).obfuscate(input, repeated)
    assertArrayEquals(Files.readAllBytes(output), Files.readAllBytes(repeated))
    assertEquals(42, execute(rewrite(output, "repacked.jar") {}))
    val metadata = "META-INF/obfaryn/"
    val edits: List<(MutableMap<String, ByteArray>) -> Unit> = listOf(
      { it["data.txt"] = byteArrayOf(1, 2, 4) },
      { it.remove("data.txt") },
      { it["extra.txt"] = byteArrayOf(9) },
      { it.remove(metadata + "integrity.sig") },
      { it.remove(metadata + "integrity.bin") },
      { val bytes = it.getValue(metadata + "integrity.sig"); bytes[0] = (bytes[0].toInt() xor 1).toByte() },
      { it.getValue(metadata + "integrity.bin")[0] = 0 },
    )
    for ((index, edit) in edits.withIndex()) {
      val error = assertThrows(ExceptionInInitializerError::class.java) { execute(rewrite(output, "bad$index.jar", edit)) }
      assertInstanceOf(SecurityException::class.java, error.cause)
    }
  }

  @Test fun rejectsSignedMalformedManifestsAndDuplicateEntries() {
    val config = config()
    val output = dir.resolve("signed.jar")
    Obfuscator(config).obfuscate(input(), output)
    val manifestName = "META-INF/obfaryn/integrity.bin"
    val signatureName = "META-INF/obfaryn/integrity.sig"
    val secret = KeyFactory.getInstance("RSA").generatePrivate(
      PKCS8EncodedKeySpec(Files.readAllBytes(config.integrityPrivateKey!!))
    )
    val mutations: List<(ByteArray) -> ByteArray> = listOf(
      { it + byteArrayOf(0) },
      { it.copyOf(it.size - 1) },
      { it.copyOf().apply { this[0] = 0 } },
      { it.copyOf().apply { this[7] = 0 } },
      { it.copyOf().apply {
        val nameLength = ((this[8].toInt() and 255) shl 8) or (this[9].toInt() and 255)
        this[10 + nameLength + 3] = 31
      } },
    )
    for ((index, mutation) in mutations.withIndex()) {
      val bad = rewrite(output, "malformed$index.jar") { entries ->
        val manifest = mutation(entries.getValue(manifestName))
        entries[manifestName] = manifest
        entries[signatureName] = java.security.Signature.getInstance("SHA256withRSA").run {
          initSign(secret); update(manifest); sign()
        }
      }
      val error = assertThrows(ExceptionInInitializerError::class.java) { execute(bad) }
      assertInstanceOf(SecurityException::class.java, error.cause)
    }
    val duplicate = rewrite(output, "duplicate.jar") { it["copy.txt"] = byteArrayOf(1, 2, 3) }
    val bytes = Files.readAllBytes(duplicate)
    val from = "copy.txt".toByteArray()
    val to = "data.txt".toByteArray()
    var replacements = 0
    for (offset in 0..bytes.size - from.size) {
      if (from.indices.all { bytes[offset + it] == from[it] }) {
        to.copyInto(bytes, offset)
        replacements++
      }
    }
    assertEquals(2, replacements)
    Files.write(duplicate, bytes)
    val error = assertThrows(ExceptionInInitializerError::class.java) { execute(duplicate) }
    assertInstanceOf(SecurityException::class.java, error.cause)
  }

  @Test fun rejectsExplodedDirectories() {
    val output = dir.resolve("signed.jar")
    Obfuscator(config()).obfuscate(input(), output)
    val exploded = dir.resolve("exploded")
    JarFile(output.toFile()).use { jar ->
      for (entry in jar.entries().toList().filterNot { it.isDirectory }) {
        val path = exploded.resolve(entry.name)
        Files.createDirectories(path.parent)
        jar.getInputStream(entry).use { Files.copy(it, path) }
      }
    }
    val error = assertThrows(ExceptionInInitializerError::class.java) { execute(exploded) }
    assertInstanceOf(SecurityException::class.java, error.cause)
  }

  @Test fun requiresMatchingKeyPair() {
    val config = config()
    assertThrows(IllegalArgumentException::class.java) {
      Obfuscator(config.copy(integrityPublicKey = null)).obfuscate(input(), dir.resolve("bad.jar"))
    }
    val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    Files.write(config.integrityPublicKey!!, other.public.encoded)
    assertThrows(IllegalArgumentException::class.java) { Obfuscator(config).obfuscate(input(), dir.resolve("bad.jar")) }
  }

  @Test fun integratesWithRenamingAndVirtualization() {
    val output = dir.resolve("transformed.jar")
    val config = config().copy(renameClasses = true, renameMethods = true, renameFields = true,
      targetPackage = "hidden", flattenPackages = true, controlFlow = true, stringEncryption = true, numberObfuscation = true,
      virtualizeMethods = listOf("Sample\\.answer\\(\\)I"))
    Obfuscator(config).obfuscate(input(), output)
    JarFile(output.toFile()).use { jar ->
      val nodes = jar.entries().toList().filter { it.name.endsWith(".class") }.map { entry ->
        org.objectweb.asm.tree.ClassNode().also { node ->
          org.objectweb.asm.ClassReader(jar.getInputStream(entry).use { it.readBytes() }).accept(node, 0)
        }
      }
      val helper = nodes.single { it.name.startsWith("obfaryn/integrity/") }
      assertEquals(Opcodes.V1_8, helper.version)
      assertFalse(helper.methods.any { method -> method.instructions.toArray().any { it is org.objectweb.asm.tree.InvokeDynamicInsnNode } })
      val sample = nodes.single { it.name.startsWith("hidden/") && it.methods.any { method -> method.desc == "()I" } }
      val method = sample.methods.single { it.desc == "()I" }
      URLClassLoader(arrayOf(output.toUri().toURL()), platformClassLoader()).use { loader ->
        assertEquals(42, loader.loadClass(sample.name.replace('/', '.')).getDeclaredMethod(method.name).invoke(null))
      }
    }
  }

  @Test fun rejectsExcludingAllClassesAndDoesNotWriteDuringDryRun() {
    val config = config()
    val input = input()
    val output = dir.resolve("dry.jar")
    Obfuscator(config.copy(dryRun = true)).obfuscate(input, output)
    assertFalse(Files.exists(output))
    assertThrows(IllegalArgumentException::class.java) {
      Obfuscator(config.copy(excludeClasses = listOf(".*"))).obfuscate(input, output)
    }
  }
}
