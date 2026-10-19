package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.ObfuscationContext as ObfuscationContextApi
import com.peco2282.obfaryn.api.transformer.TransformationPolicy
import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy
import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.transformer.FilteredContext
import com.peco2282.obfaryn.core.transformer.NumberObfuscationTransformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.util.Random

class TransformationPolicyTest {
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
