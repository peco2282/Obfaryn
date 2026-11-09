package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.core.keep.KeepRules
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.SourceInterpreter
import org.objectweb.asm.tree.analysis.SourceValue
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/** Protects names used by straightforward constant-string reflection calls. */
internal object ReflectionScanner {
  /** `MethodHandles.Lookup` finders whose first two arguments are `(Class refc, String name, ...)`. */
  private val LOOKUP_METHOD_FINDERS = setOf("findStatic", "findVirtual", "findSpecial")
  /** `findConstructor(Class refc, MethodType type)` carries no name literal; the member is always `<init>`. */
  private const val LOOKUP_CONSTRUCTOR_FINDER = "findConstructor"
  private val LOOKUP_FIELD_FINDERS = setOf(
    "findGetter", "findSetter", "findStaticGetter", "findStaticSetter", "findVarHandle", "findStaticVarHandle")
  /** Finders whose target is resolved virtually, so overriding declarations up the hierarchy must stay too. */
  private val LOOKUP_VIRTUAL_FINDERS = setOf("findVirtual", "findGetter", "findSetter")

  fun scan(context: ObfuscationContext, keep: KeepRules): List<String> {
    val warnings = linkedSetOf<String>()
    for (node in context.classes.values) for (method in node.methods) {
      val frames by lazy {
        runCatching { Analyzer(object : SourceInterpreter(Opcodes.ASM9) {
          override fun copyOperation(insn: AbstractInsnNode, value: SourceValue): SourceValue = value
        }).analyze(node.name, method) }.getOrNull()
      }
      for ((index, insn) in method.instructions.withIndex()) {
        if (insn !is MethodInsnNode) continue
        // getDeclaredMethod(name, parameterTypes) puts an array construction between
        // the name literal and the call. Scan a bounded straight-line window.
        val previous = generateSequence(insn.previous) { it.previous }.take(24)
          .takeWhile { it !is MethodInsnNode && it.opcode != Opcodes.GOTO }
          .filterIsInstance<LdcInsnNode>().firstOrNull { it.cst is String }
        val value = previous?.cst as? String
        val location = "${node.name}.${method.name}${method.desc}"
        when {
          (insn.owner == "java/lang/Class" && insn.name == "forName" && insn.desc.startsWith("(Ljava/lang/String;")) ||
            (insn.owner == "java/lang/ClassLoader" && insn.name == "loadClass" && insn.desc.startsWith("(Ljava/lang/String;")) -> {
            if (value != null && value.matches(Regex("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*"))) {
              keep.keepClass(value)
              warnings += "Possible reflective class reference: $value at $location (class kept)"
            }
          }
          insn.owner == "java/lang/Class" && insn.name in setOf("getMethod", "getDeclaredMethod", "getField", "getDeclaredField") -> {
            val frame = frames?.get(index)
            val argumentCount = Type.getArgumentTypes(insn.desc).size
            val receiver = frame?.takeIf { it.stackSize > argumentCount }
              ?.getStack(frame.stackSize - argumentCount - 1)
            val owners = receiver?.insns.orEmpty().mapNotNull { source ->
              val literal = ((source as? LdcInsnNode)?.cst as? Type)
                ?.takeIf { it.sort == Type.OBJECT }?.internalName
              if (literal != null) literal else if (source is MethodInsnNode &&
                ((source.owner == "java/lang/Class" && source.name == "forName") ||
                  (source.owner == "java/lang/ClassLoader" && source.name == "loadClass")) &&
                source.desc.startsWith("(Ljava/lang/String;")) {
                val origin = frames?.get(method.instructions.indexOf(source))
                val count = Type.getArgumentTypes(source.desc).size
                val nameSource = origin?.getStack(origin.stackSize - count)?.insns?.singleOrNull() as? LdcInsnNode
                (nameSource?.cst as? String)?.replace('.', '/')
              } else null
            }.toSet()
            val literalName = frame?.takeIf { it.stackSize >= argumentCount }
              ?.getStack(frame.stackSize - argumentCount)?.insns?.singleOrNull() as? LdcInsnNode
            val memberName = literalName?.cst as? String
            if (owners.isEmpty() || receiver?.insns?.size != owners.size || memberName == null) {
              warnings += "Unresolved reflective member target at $location; use an explicit owner/member keep rule"
              continue
            }
            val visited = mutableSetOf<String>()
            fun protect(owner: String) {
              if (!visited.add(owner)) return
              val target = context.classes[owner] ?: return
              if (insn.name in setOf("getMethod", "getDeclaredMethod")) {
                target.methods.filter { it.name == memberName }.forEach { keep.keepMethod(owner, it.name, it.desc) }
              } else {
                target.fields.filter { it.name == memberName }.forEach { keep.keepField(owner, it.name) }
              }
              if (insn.name in setOf("getMethod", "getField")) {
                target.superName?.let(::protect)
                target.interfaces.forEach(::protect)
              }
            }
            owners.forEach(::protect)
            warnings += "Reflective member target: ${owners.sorted()}:$memberName at $location (owner-scoped keep)"
          }
          insn.owner == "java/lang/invoke/MethodHandles\$Lookup" &&
            (insn.name in LOOKUP_METHOD_FINDERS || insn.name in LOOKUP_FIELD_FINDERS || insn.name == LOOKUP_CONSTRUCTOR_FINDER) -> {
            val frame = frames?.get(index)
            val argumentCount = Type.getArgumentTypes(insn.desc).size
            // Instance call on Lookup: args start at stackSize-argumentCount; refc is always arg0.
            val refc = frame?.takeIf { it.stackSize >= argumentCount }
              ?.getStack(frame.stackSize - argumentCount)?.insns?.singleOrNull() as? LdcInsnNode
            val owner = (refc?.cst as? Type)?.takeIf { it.sort == Type.OBJECT }?.internalName
            val memberName = if (insn.name == LOOKUP_CONSTRUCTOR_FINDER) "<init>" else {
              val nameLiteral = frame?.takeIf { it.stackSize >= argumentCount - 1 }
                ?.getStack(frame.stackSize - argumentCount + 1)?.insns?.singleOrNull() as? LdcInsnNode
              nameLiteral?.cst as? String
            }
            if (owner == null || memberName == null) {
              warnings += "Unresolved MethodHandles.Lookup target at $location; use an explicit owner/member keep rule"
              continue
            }
            val visited = mutableSetOf<String>()
            fun protect(candidate: String) {
              if (!visited.add(candidate)) return
              val target = context.classes[candidate] ?: return
              if (insn.name in LOOKUP_FIELD_FINDERS) {
                target.fields.filter { it.name == memberName }.forEach { keep.keepField(candidate, it.name) }
              } else {
                target.methods.filter { it.name == memberName }.forEach { keep.keepMethod(candidate, it.name, it.desc) }
              }
              if (insn.name in LOOKUP_VIRTUAL_FINDERS) {
                target.superName?.let(::protect)
                target.interfaces.forEach(::protect)
              }
            }
            protect(owner)
            warnings += "MethodHandles.Lookup target: $owner:$memberName at $location (owner-scoped keep)"
          }
        }
      }
    }
    return warnings.toList()
  }
}
