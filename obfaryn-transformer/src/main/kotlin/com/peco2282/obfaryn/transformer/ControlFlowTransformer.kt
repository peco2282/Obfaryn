package com.peco2282.obfaryn.transformer

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.api.transformer.TransformerReport
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.SourceInterpreter
import java.util.Collections
import java.util.Random
import java.util.WeakHashMap

/** Uses keyed dispatch or direct-branch block layout changes without an interpreter. */
class ControlFlowTransformer(private val random: Random, private val lightweight: Boolean = false) : Transformer {
  override val id = "control-flow"
  override val order = 35
  override val requiresFrameRecomputation = true
  var count = 0
    private set
  private val flattened = Collections.newSetFromMap(WeakHashMap<MethodNode, Boolean>())
  private var dispatchedMethods = 0L
  private var rearrangedMethods = 0L

  override fun report() = TransformerReport(id, mapOf(
    "dispatchedMethods" to dispatchedMethods, "rearrangedMethods" to rearrangedMethods,
  ))

  override fun transform(context: ObfuscationContext) {
    count = 0
    dispatchedMethods = 0
    rearrangedMethods = 0
    for (owner in context.classes.values) for (method in owner.methods) {
      if (context.transformationPolicy.canTransformBody(owner, method)) {
        if (!lightweight) flatten(owner.name, method)
        if (method !in flattened) rearrange(method)
      }
    }
  }

  private fun rearrange(method: MethodNode) {
    if (method in flattened || method.name.startsWith('<') || method.instructions.size() > 10000 ||
      method.tryCatchBlocks.isNotEmpty() || method.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) return
    val original = method.instructions.toArray().filterNot { it is FrameNode }
    if (original.any { it.opcode == Opcodes.JSR || it.opcode == Opcodes.RET }) return
    val positions = original.withIndex().associate { it.value to it.index }
    val leaders = sortedSetOf(0)
    var edges = 0
    for ((index, node) in original.withIndex()) {
      val targets = when (node) {
        is JumpInsnNode -> listOf(node.label)
        is TableSwitchInsnNode -> node.labels + node.dflt
        is LookupSwitchInsnNode -> node.labels + node.dflt
        else -> emptyList()
      }
      if (targets.isNotEmpty()) {
        edges++
        for (label in targets) leaders += positions[label] ?: return
      }
      if (targets.isNotEmpty() || node.opcode in Opcodes.IRETURN..Opcodes.RETURN || node.opcode == Opcodes.ATHROW) {
        if (index + 1 < original.size) leaders += index + 1
      }
    }
    if (edges == 0 || leaders.size !in 2..256) return
    val starts = leaders.toList()
    val blocks = starts.mapIndexed { index, start ->
      original.subList(start, starts.getOrElse(index + 1) { original.size })
    }
    val entries = Array(blocks.size) { LabelNode() }
    val labels = original.filterIsInstance<LabelNode>().associateWith { LabelNode() }
    val output = InsnList()
    output.add(JumpInsnNode(Opcodes.GOTO, entries[0]))
    val order = blocks.indices.toMutableList().also { Collections.shuffle(it, random) }
    if (order == blocks.indices.toList()) Collections.rotate(order, 1)
    for (index in order) {
      output.add(entries[index])
      val nodes = blocks[index]
      for (node in nodes) output.add(node.clone(labels))
      val last = nodes.lastOrNull { it.opcode >= 0 }
      // Preserve each original fall-through edge, including its operand stack and local types.
      if (last !is TableSwitchInsnNode && last !is LookupSwitchInsnNode &&
        last?.opcode != Opcodes.GOTO && last?.opcode !in Opcodes.IRETURN..Opcodes.RETURN &&
        last?.opcode != Opcodes.ATHROW && index + 1 < blocks.size) {
        output.add(JumpInsnNode(Opcodes.GOTO, entries[index + 1]))
      }
    }
    method.instructions.clear()
    method.instructions.add(output)
    method.localVariables?.clear()
    method.visibleLocalVariableAnnotations?.clear()
    method.invisibleLocalVariableAnnotations?.clear()
    flattened.add(method)
    rearrangedMethods++
    count += edges
  }

  private fun flatten(owner: String, method: MethodNode) {
    if (method in flattened) return
    if (method.name.startsWith('<') || method.instructions.size() < 2 || method.instructions.size() > 10000 ||
      method.tryCatchBlocks.isNotEmpty() || method.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) return

    val original = method.instructions.toArray().filterNot { it is FrameNode }
    if (original.any { it.opcode == Opcodes.JSR || it.opcode == Opcodes.RET }) return
    val jumps = original.filterIsInstance<JumpInsnNode>()
    val switches = original.filter { it is TableSwitchInsnNode || it is LookupSwitchInsnNode }
    if (jumps.isEmpty() && switches.isEmpty()) return

    val parameterKinds = mutableMapOf<Int, Int>()
    var parameterSlots = 0
    if (method.access and Opcodes.ACC_STATIC == 0) parameterKinds[parameterSlots++] = Opcodes.ALOAD
    Type.getArgumentTypes(method.desc).forEach { type ->
      parameterKinds[parameterSlots] = when (type.sort) {
        Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.ILOAD
        Type.FLOAT -> Opcodes.FLOAD
        Type.LONG -> Opcodes.LLOAD
        Type.DOUBLE -> Opcodes.DLOAD
        else -> Opcodes.ALOAD
      }
      parameterSlots += type.size
    }
    val localKinds = linkedMapOf<Int, Int>()
    fun local(slot: Int, kind: Int): Boolean {
      if (slot < parameterSlots) return parameterKinds[slot] == kind
      val previous = localKinds.putIfAbsent(slot, kind)
      return previous == null || previous == kind
    }
    for (node in original) when (node) {
      is IincInsnNode -> if (!local(node.`var`, Opcodes.ILOAD)) return
      is VarInsnNode -> {
        val kind = when (node.opcode) {
          Opcodes.ILOAD, Opcodes.ISTORE -> Opcodes.ILOAD
          Opcodes.FLOAD, Opcodes.FSTORE -> Opcodes.FLOAD
          Opcodes.LLOAD, Opcodes.LSTORE -> Opcodes.LLOAD
          Opcodes.DLOAD, Opcodes.DSTORE -> Opcodes.DLOAD
          Opcodes.ALOAD, Opcodes.ASTORE -> Opcodes.ALOAD
          else -> continue
        }
        if (!local(node.`var`, kind)) return
      }
    }

    val positions = original.withIndex().associate { it.value to it.index }
    val leaders = sortedSetOf(0)
    for (jump in jumps) {
      leaders += positions[jump.label] ?: return
      val next = positions.getValue(jump) + 1
      if (next < original.size) leaders += next
    }
    for (node in switches) {
      val labels = when (node) {
        is TableSwitchInsnNode -> node.labels + node.dflt
        is LookupSwitchInsnNode -> node.labels + node.dflt
        else -> error("Not a switch")
      }
      for (label in labels) leaders += positions[label] ?: return
      val next = positions.getValue(node) + 1
      if (next < original.size) leaders += next
    }
    val starts = leaders.toList()
    if (starts.size < 2 || starts.size > 256) return
    // Compute actual limits instead of allocating an instruction-count-sized stack for every
    // analysis frame. Earlier passes can leave the original limits stale.
    val savedMaxStack = method.maxStack
    val savedMaxLocals = method.maxLocals
    val frames = try {
      Analyzer(SourceInterpreter()).analyzeAndComputeMaxs(owner, method)
    } catch (_: Exception) {
      method.maxStack = savedMaxStack
      method.maxLocals = savedMaxLocals
      return
    }
    val computedMaxLocals = method.maxLocals
    // Eligibility analysis must not mutate methods that we ultimately leave untouched.
    method.maxStack = savedMaxStack
    method.maxLocals = savedMaxLocals
    if (starts.any { start ->
        val instruction = original[start]
        val frame = frames[method.instructions.indexOf(instruction)]
        frame == null || frame.stackSize != 0
      }) return
    val blocks = starts.mapIndexed { index, start ->
      original.subList(start, starts.getOrElse(index + 1) { original.size })
    }
    for ((blockIndex, nodes) in blocks.withIndex()) {
      val last = nodes.getOrNull(nodes.indexOfLast { it.opcode >= 0 })
      if (last is JumpInsnNode && last.opcode != Opcodes.GOTO && blockIndex + 1 >= blocks.size) return
      if (last !is JumpInsnNode && last !is TableSwitchInsnNode && last !is LookupSwitchInsnNode &&
        last?.opcode !in Opcodes.IRETURN..Opcodes.RETURN &&
        last?.opcode != Opcodes.ATHROW && blockIndex + 1 >= blocks.size) return
    }
    val blockAt = IntArray(original.size)
    blocks.forEachIndexed { block, nodes -> nodes.forEach { blockAt[positions.getValue(it)] = block } }
    fun target(label: LabelNode): Int = blockAt[positions[label] ?: error("Missing jump target")]

    val usedStates = hashSetOf<Int>()
    val states = IntArray(blocks.size) {
      var value: Int
      do value = random.nextInt() while (value == 0 || !usedStates.add(value))
      value
    }
    val stateLocal = computedMaxLocals
    method.maxLocals = computedMaxLocals + 1
    val dispatch = LabelNode()
    val defaultCase = LabelNode()
    val caseLabels = Array(blocks.size) { LabelNode() }
    val output = InsnList()

    // Only entry installs a literal state. Every subsequent edge depends on its source state.
    fun InsnList.route(block: Int, from: Int? = null) {
      if (from == null) {
        add(LdcInsnNode(states[block]))
      } else {
        add(VarInsnNode(Opcodes.ILOAD, stateLocal))
        if (random.nextBoolean()) {
          add(LdcInsnNode(states[from] xor states[block]))
          add(InsnNode(Opcodes.IXOR))
        } else {
          add(LdcInsnNode(states[block] - states[from]))
          add(InsnNode(Opcodes.IADD))
        }
      }
      add(VarInsnNode(Opcodes.ISTORE, stateLocal))
      add(JumpInsnNode(Opcodes.GOTO, dispatch))
    }

    // The dispatcher establishes this state for the entire block. Bind payloads to it rather
    // than adding another expression whose operands are all constants.
    fun InsnList.emit(node: AbstractInsnNode, block: Int) {
      val value: Number? = when (node) {
        is LdcInsnNode -> node.cst as? Number
        is IntInsnNode -> if (node.opcode in Opcodes.BIPUSH..Opcodes.SIPUSH) node.operand else null
        is InsnNode -> when (node.opcode) {
          in Opcodes.ICONST_2..Opcodes.ICONST_5 -> node.opcode - Opcodes.ICONST_0
          Opcodes.FCONST_2 -> 2.0f
          else -> null
        }
        else -> null
      }
      if (value == null || value is Int && value in -1..1 || value is Long && value in -1L..1L) {
        add(node)
        return
      }
      when (value) {
        is Int, is Float -> {
          val bits = if (value is Float) value.toRawBits() else value.toInt()
          add(VarInsnNode(Opcodes.ILOAD, stateLocal))
          add(LdcInsnNode(bits xor states[block]))
          add(InsnNode(Opcodes.IXOR))
          if (value is Float) add(MethodInsnNode(Opcodes.INVOKESTATIC,
            "java/lang/Float", "intBitsToFloat", "(I)F", false))
        }
        is Long, is Double -> {
          val bits = if (value is Double) value.toRawBits() else value.toLong()
          add(VarInsnNode(Opcodes.ILOAD, stateLocal))
          add(InsnNode(Opcodes.I2L))
          add(LdcInsnNode(bits xor states[block].toLong()))
          add(InsnNode(Opcodes.LXOR))
          if (value is Double) add(MethodInsnNode(Opcodes.INVOKESTATIC,
            "java/lang/Double", "longBitsToDouble", "(J)D", false))
        }
        else -> add(node)
      }
    }

    for ((slot, kind) in localKinds) {
      when (kind) {
        Opcodes.ILOAD -> output.add(InsnNode(Opcodes.ICONST_0))
        Opcodes.FLOAD -> output.add(InsnNode(Opcodes.FCONST_0))
        Opcodes.LLOAD -> output.add(InsnNode(Opcodes.LCONST_0))
        Opcodes.DLOAD -> output.add(InsnNode(Opcodes.DCONST_0))
        Opcodes.ALOAD -> output.add(InsnNode(Opcodes.ACONST_NULL))
      }
      output.add(VarInsnNode(when (kind) {
        Opcodes.ILOAD -> Opcodes.ISTORE
        Opcodes.FLOAD -> Opcodes.FSTORE
        Opcodes.LLOAD -> Opcodes.LSTORE
        Opcodes.DLOAD -> Opcodes.DSTORE
        else -> Opcodes.ASTORE
      }, slot))
    }
    output.route(0)
    val blockOrder = blocks.indices.toMutableList().also { Collections.shuffle(it, random) }
    for (blockIndex in blockOrder) {
      output.add(caseLabels[blockIndex])
      val nodes = blocks[blockIndex]
      val lastExecutable = nodes.indexOfLast { it.opcode >= 0 }
      for ((nodeIndex, node) in nodes.withIndex()) {
        if (nodeIndex == lastExecutable && (node is TableSwitchInsnNode || node is LookupSwitchInsnNode)) {
          val routes = linkedMapOf<LabelNode, LabelNode>()
          fun edge(label: LabelNode): LabelNode = routes.getOrPut(label) { LabelNode() }
          when (node) {
            is TableSwitchInsnNode -> output.add(TableSwitchInsnNode(node.min, node.max,
              edge(node.dflt), *node.labels.map(::edge).toTypedArray()))
            is LookupSwitchInsnNode -> output.add(LookupSwitchInsnNode(edge(node.dflt),
              node.keys.toIntArray(), node.labels.map(::edge).toTypedArray()))
          }
          for ((destination, trampoline) in routes) {
            output.add(trampoline)
            output.route(target(destination), blockIndex)
          }
        } else if (nodeIndex != lastExecutable || node !is JumpInsnNode) {
          output.emit(node, blockIndex)
        } else if (node.opcode == Opcodes.GOTO) {
          output.route(target(node.label), blockIndex)
        } else {
          val taken = LabelNode()
          output.add(JumpInsnNode(node.opcode, taken))
          output.route(blockIndex + 1, blockIndex)
          output.add(taken)
          output.route(target(node.label), blockIndex)
        }
      }
      val terminal = nodes.getOrNull(lastExecutable)?.opcode
      if (terminal !in Opcodes.IRETURN..Opcodes.RETURN && terminal != Opcodes.ATHROW &&
        nodes.getOrNull(lastExecutable) !is JumpInsnNode &&
        nodes.getOrNull(lastExecutable) !is TableSwitchInsnNode &&
        nodes.getOrNull(lastExecutable) !is LookupSwitchInsnNode) {
        output.route(blockIndex + 1, blockIndex)
      }
    }

    output.add(dispatch)
    output.add(VarInsnNode(Opcodes.ILOAD, stateLocal))
    val sorted = blocks.indices.sortedBy { states[it] }
    output.add(LookupSwitchInsnNode(defaultCase, sorted.map { states[it] }.toIntArray(),
      sorted.map { caseLabels[it] }.toTypedArray()))
    output.add(defaultCase)
    output.add(TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"))
    output.add(InsnNode(Opcodes.DUP))
    output.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false))
    output.add(InsnNode(Opcodes.ATHROW))

    method.instructions.clear()
    method.instructions.add(output)
    method.localVariables?.clear()
    flattened.add(method)
    dispatchedMethods++
    count += jumps.size + switches.size
  }
}
