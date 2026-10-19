package com.peco2282.obfaryn.core.transformer

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.transformer.Transformer
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.SourceInterpreter
import java.util.Random

/** Rewrites safe methods into per-method dispatcher state machines. */
class ControlFlowTransformer(private val random: Random) : Transformer {
  override val id = "control-flow"
  override val order = 35
  var count = 0
    private set

  override fun transform(context: ObfuscationContext) {
    for (owner in context.classes.values) for (method in owner.methods) {
      if (context.transformationPolicy.canTransformBody(owner, method)) flatten(owner.name, method)
    }
  }

  private fun flatten(owner: String, method: MethodNode) {
    if (method.name.startsWith('<') || method.instructions.size() < 2 || method.instructions.size() > 10000 ||
      method.tryCatchBlocks.isNotEmpty() || method.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) return

    val original = method.instructions.toArray().filterNot { it is FrameNode }
    if (original.any { it is TableSwitchInsnNode || it is LookupSwitchInsnNode ||
        it.opcode == Opcodes.JSR || it.opcode == Opcodes.RET }) return
    val jumps = original.filterIsInstance<JumpInsnNode>()
    if (jumps.isEmpty()) return

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
    val starts = leaders.toList()
    if (starts.size < 2 || starts.size > 256) return
    // Earlier transformers may temporarily exceed the input method's stale maxStack; output frames
    // are recomputed later by JarWriter.
    method.maxStack = maxOf(method.maxStack, method.instructions.size() + 8)
    val frames = try { Analyzer(SourceInterpreter()).analyze(owner, method) } catch (_: Exception) { return }
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
      if (last !is JumpInsnNode && last?.opcode !in Opcodes.IRETURN..Opcodes.RETURN &&
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
    val stateLocal = method.maxLocals++
    val dispatch = LabelNode()
    val defaultCase = LabelNode()
    val caseLabels = Array(blocks.size) { LabelNode() }
    val output = InsnList()

    fun InsnList.route(block: Int) {
      add(LdcInsnNode(states[block]))
      add(VarInsnNode(Opcodes.ISTORE, stateLocal))
      add(JumpInsnNode(Opcodes.GOTO, dispatch))
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
    val blockOrder = blocks.indices.toMutableList().also { java.util.Collections.shuffle(it, random) }
    for (blockIndex in blockOrder) {
      output.add(caseLabels[blockIndex])
      val nodes = blocks[blockIndex]
      val lastExecutable = nodes.indexOfLast { it.opcode >= 0 }
      for ((nodeIndex, node) in nodes.withIndex()) {
        if (nodeIndex != lastExecutable || node !is JumpInsnNode) {
          output.add(node)
        } else if (node.opcode == Opcodes.GOTO) {
          output.route(target(node.label))
        } else {
          val taken = LabelNode()
          output.add(JumpInsnNode(node.opcode, taken))
          output.route(blockIndex + 1)
          output.add(taken)
          output.route(target(node.label))
        }
      }
      val terminal = nodes.getOrNull(lastExecutable)?.opcode
      if (terminal !in Opcodes.IRETURN..Opcodes.RETURN && terminal != Opcodes.ATHROW &&
        nodes.getOrNull(lastExecutable) !is JumpInsnNode) {
        output.route(blockIndex + 1)
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
    count += jumps.size
  }
}
