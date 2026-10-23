package com.peco2282.obfaryn.transformer

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.api.protection.BodyFeature
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodNode

/** Moves application ConstantValue attributes into code for the string/number transformers. */
class ConstantFieldTransformer(
  private val applicationRoots: List<String>? = null,
  private val strings: Boolean = true,
  private val numbers: Boolean = true,
  private val keep: ((String, String) -> Boolean)? = null,
) : Transformer {
  override val isolatedBodyFeature = BodyFeature.CONSTANT_FIELDS
  override val id = "constant-fields"
  override val order = 15

  var count = 0
    private set

  override fun skipReason(context: ObfuscationContext, owner: org.objectweb.asm.tree.ClassNode,
    method: MethodNode): String = if (method.name != "<clinit>") "not-class-initializer" else "no-eligible-constant-fields"

  override fun transform(context: ObfuscationContext) {
    count = 0
    for (node in context.classes.values) {
      if (node.version < Opcodes.V1_8 || node.access and (Opcodes.ACC_ANNOTATION or Opcodes.ACC_MODULE) != 0 ||
        !(applicationRoots?.any { node.name.startsWith("$it/") }
          ?: context.transformationInputs.isApplicationClass(node.name))) continue
      val initializer = node.methods.firstOrNull { it.name == "<clinit>" }
        ?: MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
      if (!context.transformationPolicy.canTransformBody(node, initializer, BodyFeature.CONSTANT_FIELDS)) continue
      val initializers = InsnList()
      for (field in node.fields) {
        if (!context.transformationPolicy.canTransformConstant(node, field)) continue
        if (field.access and (Opcodes.ACC_STATIC or Opcodes.ACC_FINAL) != (Opcodes.ACC_STATIC or Opcodes.ACC_FINAL)) continue
        if (keep?.invoke(node.name, field.name) == true ||
          context.transformationInputs.keepConstant(node.name, field.name)) continue
        val value = field.value ?: continue
        val eligible = when (value) {
          is String -> strings && context.transformationPolicy.canTransformBody(node, initializer, BodyFeature.STRINGS)
          is Int, is Long, is Float, is Double -> numbers && context.transformationPolicy.canTransformBody(node, initializer, BodyFeature.NUMBERS)
          else -> false
        }
        if (!eligible) continue
        initializers.add(LdcInsnNode(value))
        initializers.add(FieldInsnNode(Opcodes.PUTSTATIC, node.name, field.name, field.desc))
        field.value = null
        count++
      }
      if (initializers.size() == 0) continue
      val clinit = node.methods.firstOrNull { it.name == "<clinit>" } ?: MethodNode(
        Opcodes.ACC_STATIC, "<clinit>", "()V", null, null,
      ).also { method ->
        method.instructions.add(InsnNode(Opcodes.RETURN))
        node.methods.add(method)
      }
      clinit.instructions.insert(initializers)
    }
  }
}
