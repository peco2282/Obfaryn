package com.peco2282.obfaryn.api.transformer

import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode

/** Shared keep rules and annotation intent for API-only renamers. This is a minimum, not a proof
 * that other names are safe to rename; hierarchy, reflection and framework contracts still matter. */
interface NamePreservation {
  fun keepsClassName(owner: ClassNode): Boolean = false
  fun keepsMethodName(owner: ClassNode, method: MethodNode): Boolean = false
  fun keepsFieldName(owner: ClassNode, field: FieldNode): Boolean = false
  fun keepsResourceName(path: String): Boolean = false

  companion object {
    val NONE: NamePreservation = object : NamePreservation {}
  }
}
