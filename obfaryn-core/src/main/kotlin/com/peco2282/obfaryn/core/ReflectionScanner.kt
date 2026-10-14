package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.core.keep.KeepRules
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/** Protects names used by straightforward constant-string reflection calls. */
internal object ReflectionScanner {
  fun scan(context: ObfuscationContext, keep: KeepRules): List<String> {
    val warnings = linkedSetOf<String>()
    for (node in context.classes.values) for (method in node.methods) {
      for (insn in method.instructions) {
        if (insn !is MethodInsnNode) continue
        // getDeclaredMethod(name, parameterTypes) puts an array construction between
        // the name literal and the call. Scan a bounded straight-line window.
        val previous = generateSequence(insn.previous) { it.previous }.take(24)
          .takeWhile { it !is MethodInsnNode && it.opcode != org.objectweb.asm.Opcodes.GOTO }
          .filterIsInstance<LdcInsnNode>().firstOrNull { it.cst is String } ?: continue
        val value = previous.cst as String
        val location = "${node.name}.${method.name}${method.desc}"
        when {
          (insn.owner == "java/lang/Class" && insn.name == "forName" && insn.desc.startsWith("(Ljava/lang/String;")) ||
            (insn.owner == "java/lang/ClassLoader" && insn.name == "loadClass" && insn.desc.startsWith("(Ljava/lang/String;")) -> {
            if (value.matches(Regex("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*"))) {
              keep.keepClass(value)
              warnings += "Possible reflective class reference: $value at $location (class kept)"
            }
          }
          insn.owner == "java/lang/Class" && insn.name in setOf("getMethod", "getDeclaredMethod") -> {
            keep.keepReflectiveMethod(value)
            warnings += "Possible reflective method reference: $value at $location (matching methods kept)"
          }
          insn.owner == "java/lang/Class" && insn.name in setOf("getField", "getDeclaredField") -> {
            keep.keepReflectiveField(value)
            warnings += "Possible reflective field reference: $value at $location (matching fields kept)"
          }
        }
      }
    }
    return warnings.toList()
  }
}
