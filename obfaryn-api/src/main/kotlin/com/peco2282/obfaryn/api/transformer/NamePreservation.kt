package com.peco2282.obfaryn.api.transformer

import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode

/** Shared keep rules and annotation intent for API-only renamers. This is a minimum, not a proof
 * that other names are safe to rename; hierarchy, reflection and framework contracts still matter. */
interface NamePreservation {
  /** True if [owner]'s own class name must not be renamed. Defaults to false (no opinion). */
  fun keepsClassName(owner: ClassNode): Boolean = false

  /** True if [method] on [owner] must keep its original name. Defaults to false (no opinion). */
  fun keepsMethodName(owner: ClassNode, method: MethodNode): Boolean = false

  /** True if [field] on [owner] must keep its original name. Defaults to false (no opinion). */
  fun keepsFieldName(owner: ClassNode, field: FieldNode): Boolean = false

  /** True if the resource entry at [path] must keep its original archive path. Defaults to false (no opinion). */
  fun keepsResourceName(path: String): Boolean = false

  companion object {
    /** A preservation rule that keeps nothing; use when a caller has no keep requirements of its own. */
    val NONE: NamePreservation = object : NamePreservation {}
  }
}
