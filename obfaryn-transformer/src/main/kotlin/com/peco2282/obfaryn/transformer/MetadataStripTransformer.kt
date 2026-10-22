package com.peco2282.obfaryn.transformer

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.transformer.Transformer
import org.objectweb.asm.tree.AbstractInsnNode


class MetadataStripTransformer(
  private val stripDebugInfo: Boolean = true,
  private val stripKotlinMetadata: Boolean = false,
  private val kotlinMetadataClasses: Set<String> = emptySet(),
  private val stripGenericSignatures: Boolean = false,
  private val stripMethodParameters: Boolean = true,
) : Transformer {

  override val id: String = "metadata-strip"
  override val order: Int = 10

  override fun transform(context: ObfuscationContext) {
    context.classes.values.forEach { classNode ->
      if (!context.transformationPolicy.canTransformMetadata(classNode)) return@forEach
      if (stripDebugInfo) {
        classNode.sourceFile = null
        classNode.sourceDebug = null
        classNode.visibleAnnotations?.removeIf { it.desc == "Lkotlin/jvm/internal/SourceDebugExtension;" }
        classNode.invisibleAnnotations?.removeIf { it.desc == "Lkotlin/jvm/internal/SourceDebugExtension;" }
      }
      if (stripGenericSignatures) {
        classNode.signature = null
        classNode.fields.forEach { it.signature = null }
        classNode.recordComponents?.forEach { it.signature = null }
      }
      if (stripKotlinMetadata || classNode.name in kotlinMetadataClasses ||
        context.transformationInputs.shouldStripKotlinMetadata(classNode.name)) {
        classNode.visibleAnnotations?.removeIf { it.desc == "Lkotlin/Metadata;" }
        classNode.invisibleAnnotations?.removeIf { it.desc == "Lkotlin/Metadata;" }
      }

      classNode.methods.forEach { method ->
        if (!context.transformationPolicy.canTransformBody(classNode, method)) return@forEach
        if (stripGenericSignatures) method.signature = null
        if (stripMethodParameters) method.parameters = null
        if (!stripDebugInfo) return@forEach
        method.localVariables = null
        method.visibleLocalVariableAnnotations = null
        method.invisibleLocalVariableAnnotations = null

        val iterator = method.instructions.iterator()

        while (iterator.hasNext()) {
          val instruction = iterator.next()

          if (instruction.type == AbstractInsnNode.LINE) {
            iterator.remove()
          }
        }
      }
    }
  }
}
