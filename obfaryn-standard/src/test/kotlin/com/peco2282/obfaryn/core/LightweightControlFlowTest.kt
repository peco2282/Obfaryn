package com.peco2282.obfaryn.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.JumpInsnNode
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class LightweightControlFlowTest {
  @TempDir lateinit var dir: Path

  @Test fun stackCarryingBranchesAndReusedSlotsAreNotLeftUnprotected() {
    val input = dir.resolve("input.jar")
    val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    writer.visit(V1_8, ACC_PUBLIC, "logic/Flow", null, "java/lang/Object", null)
    val m = writer.visitMethod(ACC_PUBLIC or ACC_STATIC, "calc", "(I)I", null, null)
    val negative = Label()
    val merge = Label()
    m.visitCode()
    m.visitInsn(ICONST_1)
    m.visitVarInsn(ILOAD, 0)
    m.visitJumpInsn(IFLT, negative)
    m.visitInsn(ICONST_2); m.visitVarInsn(ISTORE, 1)
    m.visitVarInsn(ILOAD, 1); m.visitInsn(IADD)
    m.visitJumpInsn(GOTO, merge)
    m.visitLabel(negative)
    m.visitInsn(ACONST_NULL); m.visitVarInsn(ASTORE, 1)
    m.visitInsn(ICONST_3); m.visitInsn(IADD)
    m.visitLabel(merge); m.visitInsn(IRETURN)
    m.visitMaxs(0, 0); m.visitEnd()
    val sum = writer.visitMethod(ACC_PUBLIC or ACC_STATIC, "sum", "(I)I", null, null)
    val loop = Label()
    val end = Label()
    val even = Label()
    val odd = Label()
    val next = Label()
    sum.visitCode()
    sum.visitInsn(ICONST_0); sum.visitVarInsn(ISTORE, 1)
    sum.visitLabel(loop)
    sum.visitVarInsn(ILOAD, 0); sum.visitJumpInsn(IFLE, end)
    sum.visitVarInsn(ILOAD, 0); sum.visitInsn(ICONST_1); sum.visitInsn(IAND)
    sum.visitTableSwitchInsn(0, 1, odd, even, odd)
    sum.visitLabel(even); sum.visitIincInsn(1, 2); sum.visitJumpInsn(GOTO, next)
    sum.visitLabel(odd); sum.visitIincInsn(1, 1)
    sum.visitLabel(next); sum.visitIincInsn(0, -1); sum.visitJumpInsn(GOTO, loop)
    sum.visitLabel(end); sum.visitVarInsn(ILOAD, 1); sum.visitInsn(IRETURN)
    sum.visitMaxs(0, 0); sum.visitEnd(); writer.visitEnd()
    JarOutputStream(Files.newOutputStream(input)).use {
      it.putNextEntry(JarEntry("logic/Flow.class")); it.write(writer.toByteArray()); it.closeEntry()
    }
    for (lightweight in listOf(false, true)) for (seed in 0L..3L) {
      val output = dir.resolve("output-$lightweight-$seed.jar")
      val result = Obfuscator(ObfuscationConfig(seed = seed, controlFlow = !lightweight,
        lightweightControlFlow = lightweight,
        renameClasses = false, renameMethods = false, renameFields = false,
        verifyOutput = true)).obfuscate(input, output)
      assertEquals(0, result.methodsVirtualized)
      assertTrue(result.branchesObfuscated > 0)
      JarFile(output.toFile()).use {
        val node = ClassNode()
        ClassReader(it.getInputStream(it.getJarEntry("logic/Flow.class"))).accept(node, 0)
        val instructions = node.methods.single { m -> m.name == "calc" }.instructions.toArray()
        assertFalse(instructions.any { n -> n.opcode == LOOKUPSWITCH || n.opcode == TABLESWITCH })
        assertTrue(instructions.filterIsInstance<JumpInsnNode>().size > 2)
        if (lightweight) assertFalse(node.methods.flatMap { m -> m.instructions.toArray().toList() }
          .any { n -> n.opcode == LOOKUPSWITCH }, "lightweight mode must never add dispatch")
      }
      URLClassLoader(arrayOf(output.toUri().toURL()), null).use {
        val calc = it.loadClass("logic.Flow").getMethod("calc", Int::class.javaPrimitiveType)
        for (n in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) {
          assertEquals(if (n < 0) 4 else 3, calc.invoke(null, n))
        }
        val sumMethod = it.loadClass("logic.Flow").getMethod("sum", Int::class.javaPrimitiveType)
        for (n in listOf(-1, 0, 1, 2, 10, 101)) {
          assertEquals((1..n).sumOf { value -> if (value % 2 == 0) 2 else 1 }, sumMethod.invoke(null, n))
        }
      }
    }
  }
}