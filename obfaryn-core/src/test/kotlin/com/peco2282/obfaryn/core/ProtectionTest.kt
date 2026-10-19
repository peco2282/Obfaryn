package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.jar.JarReader
import com.peco2282.obfaryn.core.jar.JarWriter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Label
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class ProtectionTest {
  @TempDir lateinit var dir: Path

  private fun input(): Path {
    val path = dir.resolve("input.jar")
    JarOutputStream(Files.newOutputStream(path)).use { jar ->
      for (name in listOf("logic/Calc", "fixed/Entry")) {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null)
        val m = writer.visitMethod(ACC_PUBLIC or ACC_STATIC, "calc", "(I)I", null, null)
        m.visitCode()
        if (name == "fixed/Entry") {
          m.visitVarInsn(ILOAD, 0)
          m.visitMethodInsn(INVOKESTATIC, "logic/Calc", "calc", "(I)I", false)
          m.visitInsn(IRETURN)
        } else {
          val negative = Label()
          m.visitVarInsn(ILOAD, 0); m.visitJumpInsn(IFLT, negative)
          m.visitVarInsn(ILOAD, 0); m.visitIntInsn(BIPUSH, 7); m.visitInsn(IADD); m.visitInsn(IRETURN)
          m.visitLabel(negative); m.visitVarInsn(ILOAD, 0); m.visitInsn(INEG); m.visitInsn(IRETURN)
        }
        m.visitMaxs(0, 0); m.visitEnd()
        val text = writer.visitMethod(ACC_PUBLIC or ACC_STATIC, "text", "()Ljava/lang/String;", null, null)
        text.visitCode(); text.visitLdcInsn("sensitive literal"); text.visitInsn(ARETURN); text.visitMaxs(0, 0); text.visitEnd()
        writer.visitEnd()
        jar.putNextEntry(JarEntry("$name.class")); jar.write(writer.toByteArray()); jar.closeEntry()
      }
      jar.putNextEntry(JarEntry("assets/config.yml")); jar.write("unchanged".toByteArray()); jar.closeEntry()
    }
    return path
  }

  @Test fun exclusionsRelocationAndBranchesExecuteTogether() {
    val output = dir.resolve("output.jar")
    val result = Obfuscator(ObfuscationConfig(seed = 12, targetPackage = "hidden", flattenPackages = true,
      excludeClasses = listOf("fixed/.*"), excludeResources = listOf("assets/.*"),
      renameMethods = false, renameFields = false, controlFlow = true, verifyOutput = true))
      .obfuscate(input(), output)
    val calc = Files.readAllLines(result.mapping!!).single { it.startsWith("logic/Calc ->") }.substringAfter(" -> ")
    assertTrue(calc.startsWith("hidden/"))
    JarFile(output.toFile()).use { jar ->
      assertNotNull(jar.getJarEntry("fixed/Entry.class"))
      assertEquals("unchanged", jar.getInputStream(jar.getJarEntry("assets/config.yml")).readBytes().toString(Charsets.UTF_8))
      val node = ClassNode()
      ClassReader(jar.getInputStream(jar.getJarEntry("$calc.class"))).accept(node, 0)
      val switches = node.methods.single { it.name == "calc" }.instructions.toArray().filterIsInstance<LookupSwitchInsnNode>()
      assertEquals(1, switches.size)
      assertTrue(switches.single().keys.size >= 3, "control flow must dispatch real basic blocks, not a constant one-case switch")
      val fixed = jar.getInputStream(jar.getJarEntry("fixed/Entry.class")).readBytes()
      assertTrue(fixed.toString(Charsets.ISO_8859_1).contains("sensitive literal"))
    }
    URLClassLoader(arrayOf(output.toUri().toURL()), platformClassLoader()).use { loader ->
      val method = loader.loadClass("fixed.Entry").getMethod("calc", Int::class.javaPrimitiveType)
      for (n in listOf(Int.MIN_VALUE, -10, 0, 9, Int.MAX_VALUE))
        assertEquals(if (n < 0) -n else n + 7, method.invoke(null, n))
    }
  }

  @Test fun regexIsFullMatchAndRenameOnlyDoesNotSkipOtherTransforms() {
    val result = Obfuscator(ObfuscationConfig(seed = 1, excludeClasses = listOf("logic"),
      excludeRenaming = listOf("fixed/.*"), targetPackage = "hidden", flattenPackages = true, renameMethods = false))
      .obfuscate(input(), dir.resolve("out.jar"))
    assertEquals(1, result.classesRenamed)
    assertTrue(result.stringsEncrypted >= 2)
    assertTrue(result.warnings.any { it.contains("preserved fixed") })
  }

  @Test fun invalidConfigurationFailsBeforeWriting() {
    val input = input()
    for (config in listOf(ObfuscationConfig(excludeClasses = listOf("[")),
      ObfuscationConfig(targetPackage = "bad//package", flattenPackages = true),
      ObfuscationConfig(targetPackage = "hidden", flattenPackages = true, renameClasses = false),
      ObfuscationConfig(targetPackage = "hidden"))) {
      assertThrows(IllegalArgumentException::class.java) { Obfuscator(config).obfuscate(input, dir.resolve("out.jar")) }
      assertFalse(Files.exists(dir.resolve("out.jar")))
    }
  }

  @Test fun virtualizationRunsWithAllOtherTransformsWithoutExternalRuntime() {
    val output = dir.resolve("virtual.jar")
    val result = Obfuscator(ObfuscationConfig(seed = 29, targetPackage = "hidden", flattenPackages = true, controlFlow = true,
      virtualizeMethods = listOf("logic/Calc\\.calc\\(I\\)I"), excludeClasses = listOf("fixed/.*"),
      verifyOutput = true)).obfuscate(input(), output)
    assertTrue(result.classesRenamed > 0)
    assertEquals(1, result.methodsVirtualized)
    assertTrue(result.branchesObfuscated > 0)
    URLClassLoader(arrayOf(output.toUri().toURL()), platformClassLoader()).use { loader ->
      val method = loader.loadClass("fixed.Entry").getMethod("calc", Int::class.javaPrimitiveType)
      for (n in listOf(Int.MIN_VALUE, -8, 0, 100, Int.MAX_VALUE))
        assertEquals(if (n < 0) -n else n + 7, method.invoke(null, n))
    }
  }

  @Test fun packageAccessKeepsOriginalPackageAndSeedMakesOutputReproducible() {
    val context = JarReader().read(input())
    context.classes.getValue("logic/Calc").methods.single { it.name == "calc" }.access = ACC_STATIC
    val source = dir.resolve("access.jar")
    JarWriter().write(context, source)
    val config = ObfuscationConfig(seed = 55, targetPackage = "hidden", flattenPackages = true, controlFlow = true,
      virtualizeMethods = listOf("logic/Calc\\.calc\\(I\\)I"))
    val first = dir.resolve("first.jar")
    val second = dir.resolve("second.jar")
    val result = Obfuscator(config).obfuscate(source, first)
    Obfuscator(config).obfuscate(source, second)
    assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second))
    assertTrue(result.warnings.any { it.contains("preserved logic") })
    assertTrue(Files.readAllLines(result.mapping!!).single { it.startsWith("logic/Calc ->") }.substringAfter(" -> ").startsWith("logic/"))
  }

}
