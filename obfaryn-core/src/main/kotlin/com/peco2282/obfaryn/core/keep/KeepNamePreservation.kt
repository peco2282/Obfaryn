package com.peco2282.obfaryn.core.keep

import com.peco2282.obfaryn.api.transformer.NamePreservation
import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy as Annotations
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

/** Live view: rules added by a later recipe also apply to a renamer registered by an earlier one. */
class KeepNamePreservation(private val keep: KeepRules) : NamePreservation {
  override fun keepsClassName(owner: ClassNode) = keep.className(owner.name) ||
    Annotations.noObfuscation(owner) || Annotations.keepName(owner)
  override fun keepsMethodName(owner: ClassNode, method: MethodNode) =
    keep.method(owner.name, method.name, method.desc) || Annotations.noObfuscation(owner) ||
      Annotations.noObfuscation(method) || Annotations.keepName(method) ||
      (keep.publicMembers(owner.name) && method.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0)
  override fun keepsFieldName(owner: ClassNode, field: FieldNode) =
    keep.field(owner.name, field.name) || Annotations.noObfuscation(owner) ||
      Annotations.noObfuscation(field) || Annotations.keepName(field) ||
      (keep.publicMembers(owner.name) && field.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0)
  override fun keepsResourceName(path: String) = keep.resource(path)
}
