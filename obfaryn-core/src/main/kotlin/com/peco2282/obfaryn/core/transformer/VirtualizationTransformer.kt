package com.peco2282.obfaryn.core.transformer

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.transformer.Transformer
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
import org.objectweb.asm.tree.*
import java.nio.ByteBuffer
import java.util.Base64
import java.util.Random
import java.nio.file.Path

/**
 * Replaces eligible selected methods with wrappers that execute encoded virtual programs.
 *
 * Each original-package group receives a specialized interpreter and integer-ID bridge methods
 * for operations needing ordinary JVM access. Eligibility is checked before modifying classes;
 * strict mode rejects the entire selected set if any method is unsupported.
 *
 * @param runtimeGroupSize Maximum methods sharing one interpreter; must be positive.
 * @param classpath Dependencies used when validating bridge access and hierarchy.
 * @param exactExclusions Platform policy exclusions keyed by original method identity.
 */
class VirtualizationTransformer(patterns: List<String>, private val random: Random, private val runtimeGroupSize: Int = 8,
  private val mode: VirtualizationSelectionMode = VirtualizationSelectionMode.STRICT,
  private val classpath: List<Path> = emptyList(), exclusions: List<String> = emptyList(),
  private val exactExclusions: Map<String, String> = emptyMap()) : Transformer {
  init { require(runtimeGroupSize > 0) { "Virtualization runtime group size must be positive" } }
  private val selectors = patterns.map { Regex(it) }
  private val exclusions = exclusions.map { Regex(it) }
  override val id = "virtualization"
  override val order = 5
  var count = 0
    private set

  override fun transform(context: ObfuscationContext) {
    val analysis = VirtualizationAnalyzer(selectors.map { it.pattern }, exclusions.map { it.pattern }, exactExclusions)
      .analyze(context.classes.values)
    analysis.requireEligible(mode)
    if (mode == VirtualizationSelectionMode.ANALYZE_ONLY) return
    val eligible = analysis.methods.filter { it.selected && it.eligible }.map { it.identity }.toSet()
    val selected = context.classes.values.flatMap { owner ->
      owner.methods.filter { method -> owner.name + "." + method.name + method.desc in eligible }
        .map { method -> owner to method }
    }.sortedWith(compareBy({ it.first.name }, { it.second.name }, { it.second.desc }))
    if (selected.isEmpty()) return
    val plans = selected.groupBy { it.first.name.substringBeforeLast('/', "") }.values.flatMap { methods ->
      methods.chunked(runtimeGroupSize).map { group ->
        val bridges = BridgeRegistry(random)
        bridges to group.map { (owner, method) -> Triple(owner, method, JvmToVirtualCompiler.compile(owner, method, bridges)) }
      }
    }
    val helpers = mutableListOf<ClassNode>()
    val bridgeMethods = mutableListOf<Pair<ClassNode, MethodNode>>()
    val replacements = mutableListOf<Pair<MethodNode, InsnList>>()
    for ((bridges, group) in plans) {
      val layout = VirtualLayout(random)
      var runtime: String
      val pkg = group.first().first.name.substringBeforeLast('/', "")
      do { runtime = (if (pkg.isEmpty()) "" else "$pkg/") + "V" + java.lang.Long.toUnsignedString(random.nextLong(), 36) }
      while (context.hasClass(runtime) || context.hasResource("$runtime.class") || helpers.any { it.name == runtime })
      val helper = createRuntime(runtime, layout)
      bridgeMethods.addAll(bridges.generate(helper, context.classes, classpath, bridgeMethods))
      helpers.add(helper)
      for ((owner, method, ir) in group) {
        val program = layout.encrypt(ir.encode(layout, random))
        val bytes = ByteBuffer.allocate(program.size * 4)
        program.forEach { bytes.putInt(it) }
        val encoded = Base64.getEncoder().encodeToString(bytes.array())
        require(encoded.length <= 65535) { "Virtualization program too large: ${owner.name}.${method.name}${method.desc}" }
        val body = InsnList()
        val original = method.instructions.toArray().filter { it.opcode >= 0 }
        var guard = 0
        while (guard + 2 < original.size) {
          val load = original[guard] as? VarInsnNode ?: break
          val name = original[guard + 1] as? LdcInsnNode ?: break
          val call = original[guard + 2] as? MethodInsnNode ?: break
          if (load.opcode != Opcodes.ALOAD || name.cst !is String || call.opcode != Opcodes.INVOKESTATIC ||
            call.owner != "kotlin/jvm/internal/Intrinsics" || call.name != "checkNotNullParameter" ||
            call.desc != "(Ljava/lang/Object;Ljava/lang/String;)V") break
          val callIndex = method.instructions.indexOf(call)
          if (method.tryCatchBlocks.any { handler ->
              callIndex >= method.instructions.indexOf(handler.start) && callIndex < method.instructions.indexOf(handler.end)
            }) break
          body.add(VarInsnNode(load.opcode, load.`var`)); body.add(LdcInsnNode(name.cst))
          body.add(MethodInsnNode(call.opcode, call.owner, call.name, call.desc, call.itf))
          guard += 3
        }
        body.add(LdcInsnNode(encoded))
        val types = Type.getArgumentTypes(method.desc)
        val result = Type.getReturnType(method.desc)
        val isStatic = method.access and Opcodes.ACC_STATIC != 0
        val legacy = isStatic && result.sort == Type.INT && types.all { it.sort == Type.INT }
        val slots = mutableListOf<Pair<Int, Type>>()
        var local = if (isStatic) 0 else 1
        for (type in types) { slots.add(local to type); local += type.size }
        val intInputs = slots.filter { JvmToVirtualCompiler.kind(it.second) in setOf(VirtualValueKind.INT, VirtualValueKind.FLOAT) }
        if (intInputs.isEmpty() && !legacy) body.add(InsnNode(Opcodes.ACONST_NULL)) else {
          body.add(LdcInsnNode(if (legacy) types.size else ir.locals))
          body.add(IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT))
          for ((index, type) in intInputs) {
            body.add(InsnNode(Opcodes.DUP)); body.add(LdcInsnNode(index))
            if (JvmToVirtualCompiler.kind(type) == VirtualValueKind.FLOAT) {
              body.add(VarInsnNode(Opcodes.FLOAD, index))
              body.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "floatToRawIntBits", "(F)I", false))
            } else body.add(VarInsnNode(Opcodes.ILOAD, index))
            body.add(InsnNode(Opcodes.IASTORE))
          }
        }
        if (legacy) {
          body.add(MethodInsnNode(Opcodes.INVOKESTATIC, runtime, "execute", "(Ljava/lang/String;[I)I", false))
          body.add(InsnNode(Opcodes.IRETURN))
        } else {
          val references = slots.filter { JvmToVirtualCompiler.kind(it.second) == VirtualValueKind.REFERENCE }.map { it.first } +
            if (isStatic) emptyList() else listOf(0)
          if (references.isEmpty()) body.add(InsnNode(Opcodes.ACONST_NULL)) else {
            body.add(LdcInsnNode(ir.locals))
            body.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"))
            for (index in references) {
              body.add(InsnNode(Opcodes.DUP)); body.add(LdcInsnNode(index))
              body.add(VarInsnNode(Opcodes.ALOAD, index)); body.add(InsnNode(Opcodes.AASTORE))
            }
          }
          val wideInputs = slots.filter { JvmToVirtualCompiler.kind(it.second) in setOf(VirtualValueKind.DOUBLE, VirtualValueKind.LONG) }
          if (wideInputs.isEmpty()) body.add(InsnNode(Opcodes.ACONST_NULL)) else {
            body.add(LdcInsnNode(ir.locals))
            body.add(IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_LONG))
            for ((index, type) in wideInputs) {
              body.add(InsnNode(Opcodes.DUP)); body.add(LdcInsnNode(index))
              if (JvmToVirtualCompiler.kind(type) == VirtualValueKind.DOUBLE) {
                body.add(VarInsnNode(Opcodes.DLOAD, index))
                body.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false))
              } else body.add(VarInsnNode(Opcodes.LLOAD, index))
              body.add(InsnNode(Opcodes.LASTORE))
            }
          }
          val entry = when (JvmToVirtualCompiler.kind(result)) {
            VirtualValueKind.INT -> "executeInt" to "I"
            VirtualValueKind.REFERENCE -> "executeReference" to "Ljava/lang/Object;"
            VirtualValueKind.DOUBLE -> "executeDouble" to "D"
            VirtualValueKind.LONG -> "executeLong" to "J"
            VirtualValueKind.FLOAT -> "executeFloat" to "F"
            null -> "executeVoid" to "V"
          }
          body.add(MethodInsnNode(Opcodes.INVOKESTATIC, runtime, entry.first,
            "(Ljava/lang/String;[I[Ljava/lang/Object;[J)${entry.second}", false))
          if (JvmToVirtualCompiler.kind(result) == VirtualValueKind.REFERENCE) body.add(TypeInsnNode(Opcodes.CHECKCAST, result.internalName))
          body.add(InsnNode(result.getOpcode(Opcodes.IRETURN)))
        }
        replacements.add(method to body)
      }
    }
    bridgeMethods.forEach { (owner, method) -> owner.methods.add(method) }
    helpers.forEach(context::putClass)
    for ((method, body) in replacements) {
      method.instructions = body
      method.tryCatchBlocks.clear()
      method.localVariables?.clear()
      method.visibleLocalVariableAnnotations?.clear()
      method.invisibleLocalVariableAnnotations?.clear()
      method.maxStack = 10
      method.maxLocals = maxOf(method.maxLocals, Type.getArgumentTypes(method.desc).sumOf { it.size } +
        if (method.access and Opcodes.ACC_STATIC == 0) 1 else 0)
      count++
    }
  }

  private fun createRuntime(runtime: String, layout: VirtualLayout): ClassNode {
    val original = "com/peco2282/obfaryn/core/transformer/VirtualizationRuntime"
    val helper = ClassNode()
    val stream = javaClass.getResourceAsStream("/$original.class")
      ?: error("Virtualization runtime class is missing: $original")
    stream.use { ClassReader(it).accept(ClassRemapper(helper, SimpleRemapper(original, runtime)), 0) }
    helper.version = Opcodes.V1_8
    for (method in helper.methods) for (insn in method.instructions.toArray()) {
      if (insn is MethodInsnNode && insn.owner == runtime && insn.name == "configuration") {
        val index = when (val previous = insn.previous) {
          is InsnNode -> previous.opcode - Opcodes.ICONST_0
          is IntInsnNode -> previous.operand
          is LdcInsnNode -> previous.cst as Int
          else -> error("Invalid VM configuration marker")
        }
        val mask = random.nextInt()
        val expression = InsnList().apply {
          add(LdcInsnNode(layout.constants()[index] xor mask))
          add(LdcInsnNode(mask))
          add(InsnNode(Opcodes.IXOR))
        }
        method.instructions.remove(insn.previous)
        method.instructions.insertBefore(insn, expression)
        method.instructions.remove(insn)
        method.maxStack += 2
      }
      if (insn is TableSwitchInsnNode || insn is LookupSwitchInsnNode) {
        val default = if (insn is TableSwitchInsnNode) insn.dflt else (insn as LookupSwitchInsnNode).dflt
        val labels = if (insn is TableSwitchInsnNode) insn.labels else (insn as LookupSwitchInsnNode).labels
        val keys = if (insn is TableSwitchInsnNode) (insn.min..insn.max).toList() else (insn as LookupSwitchInsnNode).keys
        val entries = keys.zip(labels).map { (key, label) ->
          layout.codes.getValue(VirtualOperation.entries[key]) to label
        }.sortedBy { it.first }
        val replacement = if (random.nextBoolean()) {
          val mapping = entries.toMap()
          val min = entries.first().first
          val max = entries.last().first
          TableSwitchInsnNode(min, max, default, *(min..max).map { mapping[it] ?: default }.toTypedArray())
        } else LookupSwitchInsnNode(default, entries.map { it.first }.toIntArray(), entries.map { it.second }.toTypedArray())
        method.instructions.set(insn, replacement)
      }
    }
    helper.methods.removeIf { it.name == "configuration" }
    return helper
  }
}
