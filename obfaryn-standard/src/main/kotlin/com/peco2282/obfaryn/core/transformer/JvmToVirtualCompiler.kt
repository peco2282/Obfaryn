package com.peco2282.obfaryn.core.transformer

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

/**
 * Converts one supported JVM method into the semantic [VirtualProgram] IR.
 *
 * Unsupported control flow, values, or constructor patterns fail with the original method
 * identity. Operations requiring JVM access are registered in [BridgeRegistry].
 */
internal object JvmToVirtualCompiler {
  private val operations = listOf(0, 18, 21, 54, 87, 89, 95, 96, 100, 104, 108, 112, 116,
    120, 122, 124, 126, 128, 130, 132, 145, 146, 147, 153, 154, 155, 156, 157, 158,
    159, 160, 161, 162, 163, 164, 167, 172).zip(VirtualOperation.entries.take(37)).toMap()

  // Lower switches without changing the input node or allocating JVM locals. The key
  // remains on the stack during comparisons and is removed exactly once on each exit.
  private fun lowerSwitches(method: MethodNode): MethodNode {
    val copy = MethodNode(method.access, method.name, method.desc, method.signature, method.exceptions.toTypedArray())
    method.accept(copy)
    for (instruction in copy.instructions.toArray()) {
      val cases: List<Pair<Int, LabelNode>>
      val default: LabelNode
      when (instruction) {
        is TableSwitchInsnNode -> {
          cases = instruction.labels.mapIndexed { index, label -> instruction.min + index to label }
          default = instruction.dflt
        }
        is LookupSwitchInsnNode -> {
          cases = instruction.keys.zip(instruction.labels)
          default = instruction.dflt
        }
        else -> continue
      }
      val exits = cases.map { LabelNode() }
      val code = InsnList()
      cases.forEachIndexed { index, (key, _) ->
        code.add(InsnNode(Opcodes.DUP))
        code.add(LdcInsnNode(key))
        code.add(JumpInsnNode(Opcodes.IF_ICMPEQ, exits[index]))
      }
      code.add(InsnNode(Opcodes.POP))
      code.add(JumpInsnNode(Opcodes.GOTO, default))
      cases.forEachIndexed { index, (_, target) ->
        code.add(exits[index])
        code.add(InsnNode(Opcodes.POP))
        code.add(JumpInsnNode(Opcodes.GOTO, target))
      }
      copy.instructions.insertBefore(instruction, code)
      copy.instructions.remove(instruction)
    }
    copy.maxStack += 2
    return copy
  }

  /** Compiles [method] without mutating its instruction list. */
  fun compile(owner: ClassNode, method: MethodNode, bridges: BridgeRegistry = BridgeRegistry(java.util.Random(0))): VirtualProgram {
    if (method.instructions.any { it is TableSwitchInsnNode || it is LookupSwitchInsnNode }) {
      return compile(owner, lowerSwitches(method), bridges)
    }
    val identity = owner.name + "." + method.name + method.desc
    fun unsupported(reason: String): Nothing = throw IllegalArgumentException("Cannot virtualize $identity: $reason")
    if (method.name.startsWith("<") ||
      method.access and (Opcodes.ACC_NATIVE or Opcodes.ACC_ABSTRACT or Opcodes.ACC_SYNCHRONIZED) != 0 ||
      !supportedType(Type.getReturnType(method.desc), true) || Type.getArgumentTypes(method.desc).any { !supportedType(it) }) {
      unsupported("requires a concrete method with int/reference/double arguments and int/reference/double/void result")
    }
    if (method.maxLocals < Type.getArgumentTypes(method.desc).sumOf { it.size } +
      (if (method.access and Opcodes.ACC_STATIC == 0) 1 else 0)) unsupported("invalid local capacity")
    val instructions = method.instructions.toArray()
    val positions = instructions.withIndex().associate { it.value to it.index }
    fun protectedRegions(index: Int): List<Int> = method.tryCatchBlocks.withIndex().filter { (_, handler) ->
      index >= positions.getValue(handler.start) && index < positions.getValue(handler.end)
    }.map { it.index }
    val branchTargets = instructions.filterIsInstance<JumpInsnNode>().map { it.label }.toSet() +
      instructions.filterIsInstance<TableSwitchInsnNode>().flatMap { it.labels + it.dflt } +
      instructions.filterIsInstance<LookupSwitchInsnNode>().flatMap { it.labels + it.dflt }
    val constructorNews = mutableSetOf<AbstractInsnNode>()
    val constructorDups = mutableSetOf<AbstractInsnNode>()
    val constructors = mutableSetOf<MethodInsnNode>()
    instructions.forEachIndexed { newIndex, candidate ->
      if (candidate !is TypeInsnNode || candidate.opcode != Opcodes.NEW) return@forEachIndexed
      val dupIndex = (newIndex + 1 until instructions.size).firstOrNull { instructions[it].opcode >= 0 } ?: return@forEachIndexed
      if (instructions[dupIndex].opcode != Opcodes.DUP) return@forEachIndexed
      var nested = 0
      for (index in dupIndex + 1 until instructions.size) {
        val current = instructions[index]
        if (current is LabelNode && current in branchTargets) break
        if (current is TypeInsnNode && current.opcode == Opcodes.NEW) {
          val nestedDup = (index + 1 until instructions.size).firstOrNull { instructions[it].opcode >= 0 }
          if (nestedDup == null || instructions[nestedDup].opcode != Opcodes.DUP) break
          nested++
          continue
        }
        if (current is JumpInsnNode || current is TableSwitchInsnNode || current is LookupSwitchInsnNode ||
          current.opcode in setOf(Opcodes.ATHROW, Opcodes.RETURN, Opcodes.IRETURN, Opcodes.LRETURN,
            Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN) ||
          current is VarInsnNode && current.opcode == Opcodes.ASTORE) break
        if (current is MethodInsnNode && current.name == "<init>") {
          if (nested > 0) { nested--; continue }
          if (current.opcode == Opcodes.INVOKESPECIAL && current.owner == candidate.desc &&
            protectedRegions(newIndex) == protectedRegions(index)) {
            constructorNews += candidate; constructorDups += instructions[dupIndex]; constructors += current
          }
          break
        }
      }
    }
    val addresses = mutableMapOf<LabelNode, Int>()
    var address = 0
    for (insn in instructions) {
      if (insn is LabelNode) addresses[insn] = address
      if (insn.opcode >= 0) address++
    }
    val program = mutableListOf<VirtualInstruction>()
    val signatures = linkedMapOf<Int, BridgeSignature>()
    val simple = setOf(0, 87, 89, 95, 96, 100, 104, 108, 112, 116, 120, 122, 124, 126, 128, 130, 145, 146, 147, 172)
    for (insn in instructions) {
      val op = insn.opcode
      if (op < 0) continue
      val target: BridgeTarget? = when (insn) {
        is FieldInsnNode -> BridgeTarget.Field(op, insn.owner, insn.name, insn.desc)
        is MethodInsnNode -> if (insn in constructors) {
          BridgeTarget.Constructor(insn.owner, insn.desc)
        } else {
          if (op == Opcodes.INVOKESPECIAL && insn.owner != owner.name && insn.owner != owner.superName && insn.owner !in owner.interfaces)
            unsupported("unsafe invokespecial owner ${insn.owner}")
          BridgeTarget.Invoke(op, insn.owner, insn.name, insn.desc, insn.itf)
        }
        is InvokeDynamicInsnNode -> BridgeTarget.InvokeDynamic(insn.name, insn.desc, insn.bsm, insn.bsmArgs.toList())
        is TypeInsnNode -> when (op) {
          Opcodes.ANEWARRAY -> BridgeTarget.NewArray(op, insn.desc)
          Opcodes.CHECKCAST -> BridgeTarget.CheckCast(insn.desc)
          Opcodes.INSTANCEOF -> BridgeTarget.InstanceOf(insn.desc)
          else -> null
        }
        is IntInsnNode -> if (op == Opcodes.NEWARRAY) BridgeTarget.NewArray(op, insn.operand.toString()) else null
        is LdcInsnNode -> when (val constant = insn.cst) {
          is String -> BridgeTarget.StringConstant(constant)
          is Type -> if (constant.sort in setOf(Type.OBJECT, Type.ARRAY)) BridgeTarget.ClassConstant(constant) else null
          else -> null
        }
        else -> null
      }
      if (target != null) {
        if (owner.access and Opcodes.ACC_INTERFACE != 0 && owner.version < Opcodes.V1_8)
          unsupported("interface bridges require Java 8 or later")
        val entry = bridges.register(owner, target)
        signatures[entry.id] = entry.signature
        val result = when (entry.signature.output) {
          null -> 0; VirtualValueKind.INT -> 1; VirtualValueKind.REFERENCE -> 2
          VirtualValueKind.DOUBLE -> 3; VirtualValueKind.LONG -> 4
          VirtualValueKind.FLOAT -> 5
        }
        program.add(VirtualInstruction(VirtualOperation.INVOKE_BRIDGE, entry.id, (entry.signature.inputs.size shl 3) or result))
        continue
      }
      val instruction = when {
        insn in constructorNews || insn in constructorDups -> listOf(Opcodes.NOP, 0, 0)
        op in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> listOf(18, op - Opcodes.ICONST_0, 0)
        op in Opcodes.DCONST_0..Opcodes.DCONST_1 -> java.lang.Double.doubleToRawLongBits((op - Opcodes.DCONST_0).toDouble()).let {
          listOf(Opcodes.DCONST_0, (it ushr 32).toInt(), it.toInt())
        }
        op in Opcodes.LCONST_0..Opcodes.LCONST_1 -> (op - Opcodes.LCONST_0).toLong().let {
          listOf(Opcodes.LCONST_0, (it ushr 32).toInt(), it.toInt())
        }
        op in Opcodes.FCONST_0..Opcodes.FCONST_2 -> java.lang.Float.floatToRawIntBits((op - Opcodes.FCONST_0).toFloat()).let {
          listOf(Opcodes.FCONST_0, it, 0)
        }
        insn is IntInsnNode && op in setOf(Opcodes.BIPUSH, Opcodes.SIPUSH) -> listOf(18, insn.operand, 0)
        insn is LdcInsnNode && insn.cst is Int -> listOf(18, insn.cst as Int, 0)
        insn is LdcInsnNode && insn.cst is Double -> java.lang.Double.doubleToRawLongBits(insn.cst as Double).let {
          listOf(Opcodes.DCONST_0, (it ushr 32).toInt(), it.toInt())
        }
        insn is LdcInsnNode && insn.cst is Long -> (insn.cst as Long).let {
          listOf(Opcodes.LCONST_0, (it ushr 32).toInt(), it.toInt())
        }
        insn is LdcInsnNode && insn.cst is Float -> listOf(Opcodes.FCONST_0,
          java.lang.Float.floatToRawIntBits(insn.cst as Float), 0)
        insn is VarInsnNode && op in setOf(Opcodes.ILOAD, Opcodes.ISTORE, Opcodes.ALOAD, Opcodes.ASTORE,
          Opcodes.DLOAD, Opcodes.DSTORE, Opcodes.LLOAD, Opcodes.LSTORE,
          Opcodes.FLOAD, Opcodes.FSTORE) -> listOf(op, insn.`var`, 0)
        insn is IincInsnNode -> listOf(op, insn.`var`, insn.incr)
        insn is JumpInsnNode && (op in 153..167 || op in setOf(Opcodes.IFNULL, Opcodes.IFNONNULL)) ->
          listOf(op, addresses[insn.label] ?: unsupported("unknown branch target"), 0)
        insn is InsnNode && (op in simple || op in setOf(Opcodes.ACONST_NULL, Opcodes.ARETURN, Opcodes.RETURN,
          Opcodes.POP2, Opcodes.DUP2, Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM,
          Opcodes.DNEG, Opcodes.DCMPL, Opcodes.DCMPG, Opcodes.I2D, Opcodes.D2I, Opcodes.DRETURN)) -> listOf(op, 0, 0)
        insn is InsnNode && op in setOf(Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM,
          Opcodes.LNEG, Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR, Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR,
          Opcodes.LCMP, Opcodes.I2L, Opcodes.L2I, Opcodes.L2D, Opcodes.D2L, Opcodes.LRETURN,
          Opcodes.ARRAYLENGTH, Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.DALOAD, Opcodes.AALOAD,
          Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD, Opcodes.IASTORE, Opcodes.LASTORE,
          Opcodes.DASTORE, Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE) -> listOf(op, 0, 0)
        insn is InsnNode && op in setOf(Opcodes.ATHROW, Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL,
          Opcodes.FDIV, Opcodes.FREM, Opcodes.FNEG, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.I2F,
          Opcodes.F2I, Opcodes.L2F, Opcodes.F2L, Opcodes.F2D, Opcodes.D2F, Opcodes.FRETURN,
          Opcodes.FALOAD, Opcodes.FASTORE) -> listOf(op, 0, 0)
        else -> unsupported("unsupported opcode $op")
      }
      val operation = when (instruction[0]) {
        Opcodes.ALOAD -> VirtualOperation.LOAD_REFERENCE
        Opcodes.ASTORE -> VirtualOperation.STORE_REFERENCE
        Opcodes.ACONST_NULL -> VirtualOperation.PUSH_NULL
        Opcodes.ARETURN -> VirtualOperation.RETURN_REFERENCE
        Opcodes.RETURN -> VirtualOperation.RETURN_VOID
        Opcodes.DCONST_0 -> VirtualOperation.DOUBLE_CONSTANT
        Opcodes.DLOAD -> VirtualOperation.LOAD_DOUBLE
        Opcodes.DSTORE -> VirtualOperation.STORE_DOUBLE
        Opcodes.POP2 -> VirtualOperation.DROP_DOUBLE
        Opcodes.DUP2 -> VirtualOperation.DUPLICATE_DOUBLE
        Opcodes.DADD -> VirtualOperation.DOUBLE_ADD
        Opcodes.DSUB -> VirtualOperation.DOUBLE_SUBTRACT
        Opcodes.DMUL -> VirtualOperation.DOUBLE_MULTIPLY
        Opcodes.DDIV -> VirtualOperation.DOUBLE_DIVIDE
        Opcodes.DREM -> VirtualOperation.DOUBLE_REMAINDER
        Opcodes.DNEG -> VirtualOperation.DOUBLE_NEGATE
        Opcodes.DCMPL -> VirtualOperation.DOUBLE_COMPARE_L
        Opcodes.DCMPG -> VirtualOperation.DOUBLE_COMPARE_G
        Opcodes.I2D -> VirtualOperation.INT_TO_DOUBLE
        Opcodes.D2I -> VirtualOperation.DOUBLE_TO_INT
        Opcodes.DRETURN -> VirtualOperation.RETURN_DOUBLE
        Opcodes.LCONST_0 -> VirtualOperation.LONG_CONSTANT
        Opcodes.LLOAD -> VirtualOperation.LOAD_LONG
        Opcodes.LSTORE -> VirtualOperation.STORE_LONG
        Opcodes.LADD -> VirtualOperation.LONG_ADD
        Opcodes.LSUB -> VirtualOperation.LONG_SUBTRACT
        Opcodes.LMUL -> VirtualOperation.LONG_MULTIPLY
        Opcodes.LDIV -> VirtualOperation.LONG_DIVIDE
        Opcodes.LREM -> VirtualOperation.LONG_REMAINDER
        Opcodes.LNEG -> VirtualOperation.LONG_NEGATE
        Opcodes.LSHL -> VirtualOperation.LONG_SHIFT_LEFT
        Opcodes.LSHR -> VirtualOperation.LONG_SHIFT_RIGHT
        Opcodes.LUSHR -> VirtualOperation.LONG_SHIFT_UNSIGNED
        Opcodes.LAND -> VirtualOperation.LONG_AND
        Opcodes.LOR -> VirtualOperation.LONG_OR
        Opcodes.LXOR -> VirtualOperation.LONG_XOR
        Opcodes.LCMP -> VirtualOperation.LONG_COMPARE
        Opcodes.I2L -> VirtualOperation.INT_TO_LONG
        Opcodes.L2I -> VirtualOperation.LONG_TO_INT
        Opcodes.L2D -> VirtualOperation.LONG_TO_DOUBLE
        Opcodes.D2L -> VirtualOperation.DOUBLE_TO_LONG
        Opcodes.LRETURN -> VirtualOperation.RETURN_LONG
        Opcodes.ARRAYLENGTH -> VirtualOperation.ARRAY_LENGTH
        Opcodes.IALOAD -> VirtualOperation.INT_ARRAY_LOAD
        Opcodes.LALOAD -> VirtualOperation.LONG_ARRAY_LOAD
        Opcodes.DALOAD -> VirtualOperation.DOUBLE_ARRAY_LOAD
        Opcodes.AALOAD -> VirtualOperation.REFERENCE_ARRAY_LOAD
        Opcodes.BALOAD -> VirtualOperation.BYTE_ARRAY_LOAD
        Opcodes.CALOAD -> VirtualOperation.CHAR_ARRAY_LOAD
        Opcodes.SALOAD -> VirtualOperation.SHORT_ARRAY_LOAD
        Opcodes.IASTORE -> VirtualOperation.INT_ARRAY_STORE
        Opcodes.LASTORE -> VirtualOperation.LONG_ARRAY_STORE
        Opcodes.DASTORE -> VirtualOperation.DOUBLE_ARRAY_STORE
        Opcodes.AASTORE -> VirtualOperation.REFERENCE_ARRAY_STORE
        Opcodes.BASTORE -> VirtualOperation.BYTE_ARRAY_STORE
        Opcodes.CASTORE -> VirtualOperation.CHAR_ARRAY_STORE
        Opcodes.SASTORE -> VirtualOperation.SHORT_ARRAY_STORE
        Opcodes.ATHROW -> VirtualOperation.THROW
        Opcodes.FCONST_0 -> VirtualOperation.FLOAT_CONSTANT
        Opcodes.FLOAD -> VirtualOperation.LOAD_FLOAT
        Opcodes.FSTORE -> VirtualOperation.STORE_FLOAT
        Opcodes.FADD -> VirtualOperation.FLOAT_ADD
        Opcodes.FSUB -> VirtualOperation.FLOAT_SUBTRACT
        Opcodes.FMUL -> VirtualOperation.FLOAT_MULTIPLY
        Opcodes.FDIV -> VirtualOperation.FLOAT_DIVIDE
        Opcodes.FREM -> VirtualOperation.FLOAT_REMAINDER
        Opcodes.FNEG -> VirtualOperation.FLOAT_NEGATE
        Opcodes.FCMPL -> VirtualOperation.FLOAT_COMPARE_L
        Opcodes.FCMPG -> VirtualOperation.FLOAT_COMPARE_G
        Opcodes.I2F -> VirtualOperation.INT_TO_FLOAT
        Opcodes.F2I -> VirtualOperation.FLOAT_TO_INT
        Opcodes.L2F -> VirtualOperation.LONG_TO_FLOAT
        Opcodes.F2L -> VirtualOperation.FLOAT_TO_LONG
        Opcodes.F2D -> VirtualOperation.FLOAT_TO_DOUBLE
        Opcodes.D2F -> VirtualOperation.DOUBLE_TO_FLOAT
        Opcodes.FRETURN -> VirtualOperation.RETURN_FLOAT
        Opcodes.FALOAD -> VirtualOperation.FLOAT_ARRAY_LOAD
        Opcodes.FASTORE -> VirtualOperation.FLOAT_ARRAY_STORE
        Opcodes.IF_ACMPEQ -> VirtualOperation.REFERENCE_EQUAL
        Opcodes.IF_ACMPNE -> VirtualOperation.REFERENCE_NOT_EQUAL
        Opcodes.IFNULL -> VirtualOperation.REFERENCE_NULL
        Opcodes.IFNONNULL -> VirtualOperation.REFERENCE_NONNULL
        else -> operations.getValue(instruction[0])
      }
      program.add(VirtualInstruction(operation, instruction[1], instruction[2]))
    }
    if (program.isEmpty()) unsupported("empty method body")
    val handlers = method.tryCatchBlocks.map { handler ->
      val bridge = handler.type?.let { type ->
        bridges.register(owner, BridgeTarget.InstanceOf(type)).also { signatures[it.id] = it.signature }.id
      } ?: 0
      VirtualExceptionHandler(addresses[handler.start] ?: unsupported("unknown exception range start"),
        addresses[handler.end] ?: unsupported("unknown exception range end"),
        addresses[handler.handler] ?: unsupported("unknown exception handler target"), bridge)
    }
    return try { VirtualProgram.fromInstructions(method.maxLocals, maxOf(1, method.maxStack), program, handlers).also {
      it.bridgeSignatures = signatures.toMap()
      if ((((10 + it.instructions.size * 6 + it.loweredExceptionHandlers.size * 4) * 4 + 2) / 3) * 4 > 65535)
        unsupported("encoded program exceeds constant pool string limit")
      val locals = MutableList<VirtualValueKind?>(method.maxLocals) { null }
      var index = 0
      if (method.access and Opcodes.ACC_STATIC == 0) locals[index++] = VirtualValueKind.REFERENCE
      for (argument in Type.getArgumentTypes(method.desc)) {
        locals[index] = kind(argument)
        index += argument.size
      }
      try { VirtualVerifier.verify(it, locals, kind(Type.getReturnType(method.desc))) }
      catch (error: IllegalArgumentException) { unsupported(error.message ?: "invalid IR") }
    } } catch (error: IllegalArgumentException) { unsupported(error.message ?: "invalid IR") }
  }

  internal fun supportedType(type: Type, allowVoid: Boolean = false): Boolean =
    type.sort in Type.BOOLEAN..Type.DOUBLE || type.sort in setOf(Type.OBJECT, Type.ARRAY) ||
      (allowVoid && type.sort == Type.VOID)

  internal fun kind(type: Type): VirtualValueKind? = when (type.sort) {
    Type.VOID -> null
    Type.OBJECT, Type.ARRAY -> VirtualValueKind.REFERENCE
    Type.DOUBLE -> VirtualValueKind.DOUBLE
    Type.LONG -> VirtualValueKind.LONG
    Type.FLOAT -> VirtualValueKind.FLOAT
    else -> VirtualValueKind.INT
  }
}
