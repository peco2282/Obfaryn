package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.ObfuscationContext as ObfuscationContextApi
import com.peco2282.obfaryn.api.transformer.TransformationPolicy
import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy
import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.transformer.FilteredContext
import com.peco2282.obfaryn.transformer.NumberObfuscationTransformer
import com.peco2282.obfaryn.transformer.ConstantFieldTransformer
import com.peco2282.obfaryn.transformer.MetadataStripTransformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.util.Random

class TransformationPolicyTest {
  @Test fun `metadata and constants preserve annotation decisions after separation`() {
    for (annotation in listOf(null, ObfuscationAnnotationPolicy.NO_OBFUSCATION,
      ObfuscationAnnotationPolicy.RENAME_ONLY, ObfuscationAnnotationPolicy.HOT_PATH)) {
      val owner = ClassNode().apply {
        name = "example/Test"
        version = Opcodes.V1_8
        sourceFile = "Test.kt"
        if (annotation != null) invisibleAnnotations = mutableListOf(AnnotationNode(annotation))
        fields.add(FieldNode(Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "value", "I", null, 42))
      }
      val context = FilteredContext(ObfuscationContext(linkedMapOf(owner.name to owner), linkedMapOf()), emptySet(), emptySet())
      MetadataStripTransformer().transform(context)
      val constants = ConstantFieldTransformer(listOf("example"), true, true) { _, _ -> false }
      constants.transform(context)
      assertEquals(if (annotation == null || annotation == ObfuscationAnnotationPolicy.HOT_PATH) null else "Test.kt", owner.sourceFile)
      assertEquals(if (annotation == null) 1 else 0, constants.count)
      assertEquals(annotation == null, owner.methods.any { it.name == "<clinit>" })
    }
  }

  @Test fun `constant conversion honors field exclusions keep predicate and custom API policy`() {
    val owner = ClassNode().apply {
      name = "example/Test"
      version = Opcodes.V1_8
      sourceFile = "Test.kt"
      for (name in listOf("annotated", "kept", "allowed")) {
        fields.add(FieldNode(Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, name, "I", null, 42).apply {
          if (name == "annotated") invisibleAnnotations = mutableListOf(AnnotationNode(ObfuscationAnnotationPolicy.NO_OBFUSCATION))
        })
      }
    }
    val delegate = ObfuscationContext(linkedMapOf(owner.name to owner), linkedMapOf())
    val denied = object : ObfuscationContextApi by delegate {
      override val transformationPolicy = object : TransformationPolicy {
        override fun canTransformBody(owner: ClassNode, method: MethodNode) = false
        override fun canTransformMetadata(owner: ClassNode) = false
        override fun canTransformConstant(owner: ClassNode, field: FieldNode) = false
      }
    }
    val constants = ConstantFieldTransformer(applicationRoots = listOf("example"), strings = true, numbers = true) { _, name -> name == "kept" }
    constants.transform(denied)
    MetadataStripTransformer().transform(denied)
    assertEquals(0, constants.count)
    assertEquals("Test.kt", owner.sourceFile)
    constants.transform(delegate)
    assertEquals(1, constants.count)
    assertEquals(listOf(42, 42, null), owner.fields.map { it.value })
  }

  @Test fun `standard and external passes share annotation decisions through filtered context`() {
    val owner = ClassNode().apply { name = "example/Test" }
    val method = MethodNode(Opcodes.ACC_STATIC, "run", "()V", null, null).apply {
      invisibleAnnotations = mutableListOf(AnnotationNode(ObfuscationAnnotationPolicy.HOT_PATH))
      instructions.add(InsnNode(Opcodes.ICONST_2))
      instructions.add(InsnNode(Opcodes.POP))
      instructions.add(InsnNode(Opcodes.RETURN))
    }
    owner.methods.add(method)
    val context = ObfuscationContext(linkedMapOf(owner.name to owner), linkedMapOf())
    val filtered = FilteredContext(context, emptySet(), emptySet())
    assertFalse(filtered.transformationPolicy.canTransformBody(owner, method))
    val transformer = NumberObfuscationTransformer(Random(0))
    transformer.transform(filtered)
    assertEquals(0, transformer.count)
    method.invisibleAnnotations.clear()
    assertTrue(filtered.transformationPolicy.canTransformBody(owner, method))
    transformer.transform(filtered)
    assertEquals(1, transformer.count)
  }

  @Test fun `standard passes honor API policy supplied by custom context`() {
    val owner = ClassNode().apply { name = "example/Test" }
    val method = MethodNode().apply { instructions.add(InsnNode(Opcodes.ICONST_2)) }
    owner.methods.add(method)
    val delegate = ObfuscationContext(linkedMapOf(owner.name to owner), linkedMapOf())
    val context = object : ObfuscationContextApi by delegate {
      override val transformationPolicy = TransformationPolicy { _, _ -> false }
    }
    val transformer = NumberObfuscationTransformer(Random(0))
    transformer.transform(context)
    assertEquals(0, transformer.count)
  }
}
