package com.peco2282.obfaryn.platform.paper

import com.peco2282.obfaryn.core.ObfuscationConfig
import com.peco2282.obfaryn.core.Obfuscator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class PaperCacheIntegrationTest {
  @TempDir
  lateinit var dir: Path

  private val runtime = "com/peco2282/obfaryn/platform/paper/cache/PaperCaches"

  private fun factory(): ByteArray {
    val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    writer.visit(V1_8, ACC_PUBLIC, "sample/Table", null, "java/lang/Object", arrayOf("java/util/function/Supplier"))
    writer.visitAnnotation("Lcom/peco2282/obfaryn/platform/paper/cache/PaperCache;", false).visitEnd()
    writer.visitField(ACC_PUBLIC or ACC_STATIC, "calls", "I", null, null).visitEnd()
    writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
      visitCode()
      visitVarInsn(ALOAD, 0)
      visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
      visitInsn(RETURN)
      visitMaxs(0, 0)
      visitEnd()
    }
    writer.visitMethod(ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null).apply {
      visitCode()
      visitFieldInsn(GETSTATIC, "sample/Table", "calls", "I")
      visitInsn(ICONST_1)
      visitInsn(IADD)
      visitFieldInsn(PUTSTATIC, "sample/Table", "calls", "I")
      visitTypeInsn(NEW, "java/lang/Object")
      visitInsn(DUP)
      visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
      visitInsn(ARETURN)
      visitMaxs(0, 0)
      visitEnd()
    }
    writer.visitEnd()
    return writer.toByteArray()
  }

  private fun entrypoint(): ByteArray {
    val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    writer.visit(V1_8, ACC_PUBLIC, "sample/Main", null, "java/lang/Object", null)
    writer.visitMethod(ACC_PUBLIC or ACC_STATIC, "test", "()I", null, null).apply {
      visitCode()
      repeat(2) {
        visitLdcInsn(Type.getObjectType("sample/Table"))
        visitMethodInsn(INVOKESTATIC, runtime, "get", "(Ljava/lang/Class;)Ljava/lang/Object;", false)
      }
      val same = Label()
      visitJumpInsn(IF_ACMPEQ, same)
      visitTypeInsn(NEW, "java/lang/AssertionError")
      visitInsn(DUP)
      visitMethodInsn(INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false)
      visitInsn(ATHROW)
      visitLabel(same)
      visitLdcInsn(Type.getObjectType("sample/Table"))
      visitMethodInsn(INVOKESTATIC, runtime, "invalidate", "(Ljava/lang/Class;)V", false)
      visitLdcInsn(Type.getObjectType("sample/Table"))
      visitMethodInsn(INVOKESTATIC, runtime, "get", "(Ljava/lang/Class;)Ljava/lang/Object;", false)
      visitInsn(POP)
      visitFieldInsn(GETSTATIC, "sample/Table", "calls", "I")
      visitInsn(IRETURN)
      visitMaxs(0, 0)
      visitEnd()
    }
    writer.visitEnd()
    return writer.toByteArray()
  }

  @Test
  fun standaloneObfuscatedJarCachesWithRenamedClassesMethodsAndFields() {
    val input = dir.resolve("input.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      for ((path, bytes) in mapOf(
        "sample/Table.class" to factory(),
        "sample/Main.class" to entrypoint(),
        "paper-plugin.yml" to "name: CacheTest\nmain: sample.Main\n".toByteArray(),
      )) {
        jar.putNextEntry(JarEntry(path))
        jar.write(bytes)
        jar.closeEntry()
      }
    }
    val output = dir.resolve("output.jar")
    val result = Obfuscator(ObfuscationConfig(
      platform = "paper", seed = 42, verifyOutput = true, controlFlow = true,
      applicationPackages = listOf("sample", "com.peco2282.obfaryn.platform.paper.cache"),
      flattenPackages = true, targetPackage = "hidden",
      platformConfiguration = { keepMethod("sample/Main", "test", "()I") },
    )).obfuscate(input, output)
    assertEquals("paper", result.platform)
    assertTrue(result.classesRenamed > 0)
    assertTrue(result.methodsRenamed > 0)
    assertTrue(result.fieldsRenamed > 0)
    val classes = JarFile(output.toFile()).use { jar ->
      assertNull(jar.getJarEntry("sample/Table.class"))
      assertNull(jar.getJarEntry("$runtime.class"))
      jar.entries().asSequence().filter { it.name.endsWith(".class") }.associate { entry ->
        val bytes = jar.getInputStream(entry).use { it.readBytes() }
        ClassReader(bytes).className.replace('/', '.') to bytes
      }
    }
    fun isolatedLoader() = object : ClassLoader(null) {
      override fun findClass(name: String): Class<*> {
        val bytes = classes[name] ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
      }
    }
    repeat(2) {
      val main = isolatedLoader().loadClass("sample.Main")
      assertEquals(2, main.getMethod("test").invoke(null))
    }
  }
}