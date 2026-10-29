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

  @Test fun switchMethodsAreFlattenedWithoutVirtualization() {
    for (dense in listOf(true, false)) {
      val input = dir.resolve("switch-$dense.jar")
      val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
      writer.visit(V1_8, ACC_PUBLIC, "logic/Switch", null, "java/lang/Object", null)
      val m = writer.visitMethod(ACC_PUBLIC or ACC_STATIC, "calc", "(I)I", null, null)
      val first = Label()
      val second = Label()
      val fallback = Label()
      m.visitCode()
      m.visitVarInsn(ILOAD, 0)
      if (dense) m.visitTableSwitchInsn(0, 2, fallback, first, second, first)
      else m.visitLookupSwitchInsn(fallback, intArrayOf(0, 100, 200), arrayOf(first, second, first))
      m.visitLabel(first); m.visitIntInsn(BIPUSH, 17); m.visitInsn(IRETURN)
      m.visitLabel(second); m.visitIntInsn(BIPUSH, 23); m.visitInsn(IRETURN)
      m.visitLabel(fallback); m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN)
      m.visitMaxs(0, 0); m.visitEnd(); writer.visitEnd()
      JarOutputStream(Files.newOutputStream(input)).use { jar ->
        jar.putNextEntry(JarEntry("logic/Switch.class")); jar.write(writer.toByteArray()); jar.closeEntry()
      }
      val output = dir.resolve("switch-$dense-out.jar")
      val result = Obfuscator(ObfuscationConfig(seed = 42, controlFlow = true,
        renameClasses = false, renameMethods = false, renameFields = false,
        verifyOutput = true)).obfuscate(input, output)
      assertEquals(0, result.methodsVirtualized)
      assertTrue(result.branchesObfuscated > 0, "switch-only methods must not be skipped")
      JarFile(output.toFile()).use { jar ->
        val node = ClassNode()
        ClassReader(jar.getInputStream(jar.getJarEntry("logic/Switch.class"))).accept(node, 0)
        assertTrue(node.methods.single().instructions.toArray().filterIsInstance<LookupSwitchInsnNode>()
          .any { it.keys.size >= 4 }, "a dispatcher must cover the entry and all switch targets")
      }
      URLClassLoader(arrayOf(output.toUri().toURL()), platformClassLoader()).use { loader ->
        val method = loader.loadClass("logic.Switch").getMethod("calc", Int::class.javaPrimitiveType)
        for (n in listOf(Int.MIN_VALUE, -1, 0, 1, 2, 100, 200, Int.MAX_VALUE)) {
          assertEquals(when (n) {
            0, (if (dense) 2 else 200) -> 17
            (if (dense) 1 else 100) -> 23
            else -> n
          }, method.invoke(null, n))
        }
      }
    }
  }

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

  @Test fun controlFlowComputesStaleLimitsAndResetsInvocationCounts() {
    val context = JarReader().read(input())
    val method = context.classes.getValue("logic/Calc").methods.single { it.name == "calc" }
    method.maxStack = 0
    method.maxLocals = 0
    val transformer = com.peco2282.obfaryn.transformer.ControlFlowTransformer(java.util.Random(42))
    assertTrue(transformer.requiresFrameRecomputation)
    transformer.transform(context)
    assertTrue(transformer.count > 0)
    assertTrue(method.maxLocals >= 2, "dispatcher must not overwrite the argument slot")
    val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    context.classes.getValue("logic/Calc").accept(writer)
    val bytes = writer.toByteArray()
    val loaded = object : ClassLoader(platformClassLoader()) {
      fun define(): Class<*> = defineClass("logic.Calc", bytes, 0, bytes.size)
    }.define()
    val calc = loaded.getMethod("calc", Int::class.javaPrimitiveType)
    assertEquals(16, calc.invoke(null, 9))
    assertEquals(10, calc.invoke(null, -10))
    transformer.transform(context)
    assertEquals(0, transformer.count, "already flattened methods must not retain prior counts")
  }

  @Test fun rejectedControlFlowAnalysisPreservesInputLimitsAndInstructions() {
    val context = JarReader().read(input())
    val method = context.classes.getValue("logic/Calc").methods.single { it.name == "calc" }
    // Both paths carry a value: valid JVM bytecode, but unsuitable for this dispatcher's
    // empty-stack block boundaries.
    method.instructions.clear()
    val negative = org.objectweb.asm.tree.LabelNode()
    method.instructions.add(org.objectweb.asm.tree.InsnNode(ICONST_1))
    method.instructions.add(org.objectweb.asm.tree.VarInsnNode(ILOAD, 0))
    method.instructions.add(org.objectweb.asm.tree.JumpInsnNode(IFLT, negative))
    method.instructions.add(org.objectweb.asm.tree.InsnNode(IRETURN))
    method.instructions.add(negative)
    method.instructions.add(org.objectweb.asm.tree.InsnNode(IRETURN))
    method.maxStack = 2
    method.maxLocals = 1
    val transformer = com.peco2282.obfaryn.transformer.ControlFlowTransformer(java.util.Random(42))
    transformer.transform(context)
    assertTrue(transformer.count > 0, "rejected dispatcher analysis must use the direct-branch fallback")
    assertEquals(2, method.maxStack)
    assertEquals(1, method.maxLocals)
    assertFalse(method.instructions.toArray().any { it.opcode == LOOKUPSWITCH })
    val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    context.classes.getValue("logic/Calc").accept(writer)
    val bytes = writer.toByteArray()
    val loaded = object : ClassLoader(platformClassLoader()) {
      fun define(): Class<*> = defineClass("logic.Calc", bytes, 0, bytes.size)
    }.define()
    val calc = loaded.getMethod("calc", Int::class.javaPrimitiveType)
    assertEquals(1, calc.invoke(null, -1))
    assertEquals(1, calc.invoke(null, 0))
  }

  @Test fun stateDependentConstantsPreserveWideValuesAndFloatingPointBits() {
    val context = JarReader().read(input())
    val owner = context.classes.getValue("logic/Calc")
    owner.methods.clear()
    val values = listOf<Any>(Int.MIN_VALUE, Int.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE,
      Float.fromBits(0x7fc01234), -0.0f, Double.fromBits(0x7ff8000000001234L), -0.0)
    for ((index, value) in values.withIndex()) {
      val desc = when (value) { is Int -> "I"; is Long -> "J"; is Float -> "F"; else -> "D" }
      val method = org.objectweb.asm.tree.MethodNode(ACC_PUBLIC or ACC_STATIC, "value$index", "(I)$desc", null, null)
      val alternate = org.objectweb.asm.tree.LabelNode()
      method.instructions.add(org.objectweb.asm.tree.VarInsnNode(ILOAD, 0))
      method.instructions.add(org.objectweb.asm.tree.JumpInsnNode(IFLT, alternate))
      method.instructions.add(org.objectweb.asm.tree.LdcInsnNode(value))
      method.instructions.add(org.objectweb.asm.tree.InsnNode(when (value) {
        is Int -> IRETURN; is Long -> LRETURN; is Float -> FRETURN; else -> DRETURN
      }))
      method.instructions.add(alternate)
      method.instructions.add(org.objectweb.asm.tree.LdcInsnNode(value))
      method.instructions.add(org.objectweb.asm.tree.InsnNode(when (value) {
        is Int -> IRETURN; is Long -> LRETURN; is Float -> FRETURN; else -> DRETURN
      }))
      owner.methods.add(method)
    }
    val transformer = com.peco2282.obfaryn.transformer.ControlFlowTransformer(java.util.Random(18))
    transformer.transform(context)
    assertEquals(values.size, transformer.count)
    for (method in owner.methods) {
      val stateStores = method.instructions.toArray().filterIsInstance<org.objectweb.asm.tree.VarInsnNode>()
        .filter { it.opcode == ISTORE && it.`var` == 1 }
      assertTrue(stateStores.size >= 3)
      assertTrue(stateStores.drop(1).all { it.previous.opcode == IXOR || it.previous.opcode == IADD })
    }
    val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    owner.accept(writer)
    val bytes = writer.toByteArray()
    val loaded = object : ClassLoader(platformClassLoader()) {
      fun define(): Class<*> = defineClass("logic.Calc", bytes, 0, bytes.size)
    }.define()
    for ((index, expected) in values.withIndex()) for (argument in listOf(-1, 0, 123)) {
      val actual = loaded.getMethod("value$index", Int::class.javaPrimitiveType).invoke(null, argument)
      when (expected) {
        is Float -> assertEquals(expected.toRawBits(), (actual as Float).toRawBits())
        is Double -> assertEquals(expected.toRawBits(), (actual as Double).toRawBits())
        else -> assertEquals(expected, actual)
      }
    }
  }

  @Test fun stateDependentTransitionsExecuteBackEdgesAcrossSeeds() {
    for (seed in 0L..7L) {
      val context = JarReader().read(input())
      val owner = context.classes.getValue("logic/Calc")
      owner.methods.clear()
      val method = org.objectweb.asm.tree.MethodNode(ACC_PUBLIC or ACC_STATIC, "sum", "(I)I", null, null)
      val loop = Label()
      val end = Label()
      method.visitInsn(ICONST_0)
      method.visitVarInsn(ISTORE, 1)
      method.visitLabel(loop)
      method.visitVarInsn(ILOAD, 0)
      method.visitJumpInsn(IFLE, end)
      method.visitVarInsn(ILOAD, 1)
      method.visitVarInsn(ILOAD, 0)
      method.visitIntInsn(BIPUSH, 7)
      method.visitInsn(IMUL)
      method.visitInsn(IADD)
      method.visitVarInsn(ISTORE, 1)
      method.visitIincInsn(0, -1)
      method.visitJumpInsn(GOTO, loop)
      method.visitLabel(end)
      method.visitVarInsn(ILOAD, 1)
      method.visitInsn(IRETURN)
      owner.methods.add(method)
      val transformer = com.peco2282.obfaryn.transformer.ControlFlowTransformer(java.util.Random(seed))
      transformer.transform(context)
      assertEquals(2, transformer.count)
      val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
      owner.accept(writer)
      val bytes = writer.toByteArray()
      val loaded = object : ClassLoader(platformClassLoader()) {
        fun define(): Class<*> = defineClass("logic.Calc", bytes, 0, bytes.size)
      }.define()
      val sum = loaded.getMethod("sum", Int::class.javaPrimitiveType)
      for (n in listOf(-1, 0, 1, 7, 1000)) {
        assertEquals(if (n <= 0) 0 else n * (n + 1) / 2 * 7, sum.invoke(null, n), "seed=$seed n=$n")
      }
    }
  }

}
