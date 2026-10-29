package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy as Policy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.tree.*

class HotPathPolicyTest {
  @Test fun `hot paths skip body transforms but explicit virtualization overrides class policy`() {
    val owner = ClassNode().apply { invisibleAnnotations = mutableListOf(AnnotationNode(Policy.HOT_PATH)) }
    val method = MethodNode()
    assertTrue(Policy.skipBody(owner, method))
    method.invisibleAnnotations = mutableListOf(AnnotationNode(Policy.VIRTUALIZE))
    assertFalse(Policy.skipBody(owner, method))
    owner.invisibleAnnotations.clear()
    method.invisibleAnnotations = mutableListOf(AnnotationNode(Policy.HOT_PATH))
    assertTrue(Policy.skipBody(owner, method))
  }
}
