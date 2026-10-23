package com.peco2282.obfaryn.transformer.linkage

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.api.transformer.TransformerReport
import com.peco2282.obfaryn.runtime.ProtectionRuntime
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode

/** Ensures the explicit prelink API exists even if every application class was excluded. */
class PrelinkRuntimeTransformer : Transformer {
  override val id = "prelink-runtime"
  override val order = 46
  override val requires = setOf("call-indirection")
  private var inserted = false
  override fun transform(context: ObfuscationContext) { inserted = inject(context) }
  override fun report() = TransformerReport(id, mapOf("runtimeClassesInjected" to if (inserted) 1L else 0L))

  companion object {
    const val RUNTIME_NAME = "com/peco2282/obfaryn/runtime/ProtectionRuntime"
    internal fun inject(context: ObfuscationContext): Boolean {
      val runtime = ClassNode()
      ProtectionRuntime::class.java.getResourceAsStream("/$RUNTIME_NAME.class")!!.use {
        ClassReader(it).accept(runtime, ClassReader.SKIP_DEBUG)
      }
      fun bytes(node: ClassNode): ByteArray {
        val raw = ClassWriter(0).also { node.accept(it) }.toByteArray()
        val normalized = ClassNode()
        ClassReader(raw).accept(normalized, ClassReader.SKIP_DEBUG)
        normalized.signature = null
        normalized.fields.forEach { it.signature = null }
        normalized.methods.forEach { it.signature = null }
        return ClassWriter(0).also { normalized.accept(it) }.toByteArray()
      }
      val existing = context.findClass(RUNTIME_NAME)
      if (existing != null) {
        require(bytes(existing).contentEquals(bytes(runtime))) { "Prelink runtime class collision: $RUNTIME_NAME" }
        return false
      }
      require(!context.hasResource("$RUNTIME_NAME.class")) { "Prelink runtime resource collision: $RUNTIME_NAME" }
      context.putClass(runtime)
      return true
    }
  }
}
