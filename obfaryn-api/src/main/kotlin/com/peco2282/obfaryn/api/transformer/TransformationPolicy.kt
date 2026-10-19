package com.peco2282.obfaryn.api.transformer

import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

/** Shared body-transformation decisions. This is not a virtualization eligibility check. */
fun interface TransformationPolicy {
  fun canTransformBody(owner: ClassNode, method: MethodNode): Boolean

  companion object {
    val ALLOW_ALL = TransformationPolicy { _, _ -> true }
  }
}