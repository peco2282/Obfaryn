package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy as Policy
import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.transformer.VirtualizationAnalyzer
import com.peco2282.obfaryn.transformer.NumberObfuscationTransformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.MethodNode
import java.util.Random

class NoVirtualizeTest {
  private fun method(name: String, annotation: String? = null) = MethodNode(
    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, "()I", null, null
  ).apply {
    annotation?.let { invisibleAnnotations = mutableListOf(AnnotationNode(it)) }
    instructions.add(IntInsnNode(Opcodes.BIPUSH, 42))
    instructions.add(InsnNode(Opcodes.IRETURN))
    maxStack = 1
  }

  private fun owner(classAnnotation: String? = null, vararg methods: MethodNode) = ClassNode().apply {
    version = Opcodes.V1_8
    access = Opcodes.ACC_PUBLIC
    name = "sample/Sample"
    superName = "java/lang/Object"
    classAnnotation?.let { invisibleAnnotations = mutableListOf(AnnotationNode(it)) }
    this.methods.addAll(methods)
  }

  @Test fun `method NoVirtualize opts out of class Virtualize but siblings stay selected`() {
    val excluded = method("excluded", Policy.NO_VIRTUALIZE)
    val kept = method("kept")
    val report = VirtualizationAnalyzer(emptyList()).analyze(listOf(owner(Policy.VIRTUALIZE, excluded, kept)))

    val excludedResult = report.methods.single { it.name == "excluded" }
    assertFalse(excludedResult.selected)
    assertEquals("@NoVirtualize on method", excludedResult.exclusionReason)
    assertTrue(report.methods.single { it.name == "kept" }.selected)
  }

  @Test fun `class NoVirtualize blocks virtualization while a method Virtualize overrides it`() {
    val optIn = method("optIn", Policy.VIRTUALIZE)
    val plain = method("plain")
    val report = VirtualizationAnalyzer(listOf("sample/Sample\\..*"))
      .analyze(listOf(owner(Policy.NO_VIRTUALIZE, optIn, plain)))

    val optInResult = report.methods.single { it.name == "optIn" }
    assertTrue(optInResult.selected)
    assertNull(optInResult.exclusionReason)
    assertEquals("@NoVirtualize on class", report.methods.single { it.name == "plain" }.exclusionReason)
  }

  @Test fun `Virtualize and NoVirtualize on the same declaration fail validation`() {
    val conflicting = method("conflicting").apply {
      invisibleAnnotations = mutableListOf(AnnotationNode(Policy.VIRTUALIZE), AnnotationNode(Policy.NO_VIRTUALIZE))
    }
    assertThrows(IllegalArgumentException::class.java) {
      VirtualizationAnalyzer(emptyList()).analyze(listOf(owner(methods = arrayOf(conflicting))))
    }
    assertThrows(IllegalArgumentException::class.java) {
      Policy.validate(listOf(owner(methods = arrayOf(conflicting))))
    }
  }

  @Test fun `NoVirtualize still allows other body passes unlike HotPath`() {
    val excluded = method("excluded", Policy.NO_VIRTUALIZE)
    val target = owner(Policy.VIRTUALIZE, excluded)
    val before = excluded.instructions.toArray().toList()
    NumberObfuscationTransformer(Random(1)).transform(
      ObfuscationContext(linkedMapOf(target.name to target), linkedMapOf())
    )
    assertNotEquals(before, excluded.instructions.toArray().toList())
  }
}
