package com.peco2282.obfaryn.core.transformer

import java.util.Random

/** Semantic interpreter operations, independent of the original JVM opcode numbers. */
internal enum class VirtualOperation {
  NOOP, CONSTANT, LOAD, STORE, DROP, DUPLICATE, SWAP,
  ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER, NEGATE,
  SHIFT_LEFT, SHIFT_RIGHT, SHIFT_UNSIGNED, AND, OR, XOR,
  INCREMENT, BYTE, CHAR, SHORT, ZERO, NONZERO,
  NEGATIVE, NONNEGATIVE, POSITIVE, NONPOSITIVE, EQUAL,
  UNEQUAL, LESS, GREATER_EQUAL, GREATER, LESS_EQUAL,
  JUMP, RETURN, ADD_CONSTANT, MULTIPLY_CONSTANT,
  LOAD_REFERENCE, STORE_REFERENCE, PUSH_NULL, REFERENCE_EQUAL, REFERENCE_NOT_EQUAL,
  REFERENCE_NULL, REFERENCE_NONNULL, RETURN_REFERENCE, RETURN_VOID, INVOKE_BRIDGE,
  DOUBLE_CONSTANT, LOAD_DOUBLE, STORE_DOUBLE, DROP_DOUBLE, DUPLICATE_DOUBLE,
  DOUBLE_ADD, DOUBLE_SUBTRACT, DOUBLE_MULTIPLY, DOUBLE_DIVIDE, DOUBLE_REMAINDER, DOUBLE_NEGATE,
  DOUBLE_COMPARE_L, DOUBLE_COMPARE_G, INT_TO_DOUBLE, DOUBLE_TO_INT, RETURN_DOUBLE,
  LONG_CONSTANT, LOAD_LONG, STORE_LONG, DROP_LONG, DUPLICATE_LONG,
  LONG_ADD, LONG_SUBTRACT, LONG_MULTIPLY, LONG_DIVIDE, LONG_REMAINDER, LONG_NEGATE,
  LONG_SHIFT_LEFT, LONG_SHIFT_RIGHT, LONG_SHIFT_UNSIGNED, LONG_AND, LONG_OR, LONG_XOR, LONG_COMPARE,
  INT_TO_LONG, LONG_TO_INT, LONG_TO_DOUBLE, DOUBLE_TO_LONG, RETURN_LONG,
  ARRAY_LENGTH, INT_ARRAY_LOAD, LONG_ARRAY_LOAD, DOUBLE_ARRAY_LOAD, REFERENCE_ARRAY_LOAD,
  BYTE_ARRAY_LOAD, CHAR_ARRAY_LOAD, SHORT_ARRAY_LOAD,
  INT_ARRAY_STORE, LONG_ARRAY_STORE, DOUBLE_ARRAY_STORE, REFERENCE_ARRAY_STORE,
  BYTE_ARRAY_STORE, CHAR_ARRAY_STORE, SHORT_ARRAY_STORE,
  THROW, FLOAT_CONSTANT, LOAD_FLOAT, STORE_FLOAT,
  FLOAT_ADD, FLOAT_SUBTRACT, FLOAT_MULTIPLY, FLOAT_DIVIDE, FLOAT_REMAINDER, FLOAT_NEGATE,
  FLOAT_COMPARE_L, FLOAT_COMPARE_G, INT_TO_FLOAT, FLOAT_TO_INT, LONG_TO_FLOAT, FLOAT_TO_LONG,
  FLOAT_TO_DOUBLE, DOUBLE_TO_FLOAT, RETURN_FLOAT, FLOAT_ARRAY_LOAD, FLOAT_ARRAY_STORE;

  val isBranch: Boolean get() = this in ZERO..LESS_EQUAL || this in REFERENCE_EQUAL..REFERENCE_NONNULL
  val isReturn: Boolean get() = this == RETURN || this == RETURN_REFERENCE || this == RETURN_VOID ||
    this == RETURN_DOUBLE || this == RETURN_LONG || this == RETURN_FLOAT || this == THROW
}

/** Runtime stack and local value categories; null references differ from integer zero. */
internal enum class VirtualValueKind { INT, REFERENCE, DOUBLE, LONG, FLOAT }
internal data class BridgeSignature(val inputs: List<VirtualValueKind>, val output: VirtualValueKind?)

/** Instruction ranges before lowering; typeBridge == 0 denotes a catch-all handler. */
internal data class VirtualExceptionHandler(val start: Int, val end: Int, val target: Int, val typeBridge: Int)

internal data class VirtualInstruction(val operation: VirtualOperation, val operand: Int = 0, val extra: Int = 0)

/** A basic block with an explicit exit, so layout changes do not rely on JVM fallthrough. */
internal data class VirtualBlock(val id: Int, val instructions: List<VirtualInstruction>, val terminator: VirtualTerminator)

internal sealed interface VirtualTerminator {
  data class Jump(val target: Int) : VirtualTerminator
  data class Branch(val condition: VirtualOperation, val trueTarget: Int, val falseTarget: Int) : VirtualTerminator
  data object Return : VirtualTerminator
  data object ReturnReference : VirtualTerminator
  data object ReturnVoid : VirtualTerminator
  data object ReturnDouble : VirtualTerminator
  data object ReturnLong : VirtualTerminator
  data object ReturnFloat : VirtualTerminator
  data object Throw : VirtualTerminator
}

/**
 * Verified method program before runtime-specific encoding.
 *
 * [locals] uses JVM local indexes, including two slots for long and double values. [blocks]
 * preserve control flow independently from their eventual serialized order.
 */
internal data class VirtualProgram(val locals: Int, val stack: Int, val blocks: List<VirtualBlock>,
  var bridgeSignatures: Map<Int, BridgeSignature> = emptyMap(),
  val exceptionHandlers: List<VirtualExceptionHandler> = emptyList()) {
  val instructions: List<VirtualInstruction> get() = lower(blocks)

  private fun addresses(ordered: List<VirtualBlock>): Map<Int, Int> {
    val addresses = mutableMapOf<Int, Int>()
    var address = 1
    for (block in ordered) {
      require(addresses.put(block.id, address) == null) { "duplicate block ID" }
      address += block.instructions.size + if (block.terminator is VirtualTerminator.Branch) 2 else 1
    }
    return addresses
  }

  private fun handlers(ordered: List<VirtualBlock>): List<VirtualExceptionHandler> {
    val addresses = addresses(ordered)
    // Keep handler priority while splitting protected ranges across shuffled blocks.
    return exceptionHandlers.flatMap { handler ->
      ordered.filter { it.id >= handler.start && it.id < handler.end }.map { block ->
        val start = addresses.getValue(block.id)
        val length = block.instructions.size + if (block.terminator is VirtualTerminator.Branch) 2 else 1
        VirtualExceptionHandler(start, start + length, addresses.getValue(handler.target), handler.typeBridge)
      }
    }
  }

  val loweredExceptionHandlers: List<VirtualExceptionHandler> get() = handlers(blocks)

  private fun lower(ordered: List<VirtualBlock>): List<VirtualInstruction> {
    val addresses = addresses(ordered)
    fun target(id: Int) = addresses[id] ?: throw IllegalArgumentException("Unknown block $id")
    return buildList {
      add(VirtualInstruction(VirtualOperation.JUMP, target(blocks.first().id)))
      for (block in ordered) {
        addAll(block.instructions)
        when (val end = block.terminator) {
          is VirtualTerminator.Jump -> add(VirtualInstruction(VirtualOperation.JUMP, target(end.target)))
          is VirtualTerminator.Branch -> {
            add(VirtualInstruction(end.condition, target(end.trueTarget)))
            add(VirtualInstruction(VirtualOperation.JUMP, target(end.falseTarget)))
          }
          VirtualTerminator.Return -> add(VirtualInstruction(VirtualOperation.RETURN))
          VirtualTerminator.ReturnReference -> add(VirtualInstruction(VirtualOperation.RETURN_REFERENCE))
          VirtualTerminator.ReturnVoid -> add(VirtualInstruction(VirtualOperation.RETURN_VOID))
          VirtualTerminator.ReturnDouble -> add(VirtualInstruction(VirtualOperation.RETURN_DOUBLE))
          VirtualTerminator.ReturnLong -> add(VirtualInstruction(VirtualOperation.RETURN_LONG))
          VirtualTerminator.ReturnFloat -> add(VirtualInstruction(VirtualOperation.RETURN_FLOAT))
          VirtualTerminator.Throw -> add(VirtualInstruction(VirtualOperation.THROW))
        }
      }
    }
  }

  fun encode(layout: VirtualLayout, random: Random): IntArray {
    val ordered = blocks.shuffled(random).map { block ->
      val body = mutableListOf<VirtualInstruction>()
      var index = 0
      while (index < block.instructions.size) {
        val first = block.instructions[index]
        val next = block.instructions.getOrNull(index + 1)
        val fused = when (next?.operation) {
          VirtualOperation.ADD -> VirtualOperation.ADD_CONSTANT
          VirtualOperation.MULTIPLY -> VirtualOperation.MULTIPLY_CONSTANT
          else -> null
        }
        if (first.operation == VirtualOperation.CONSTANT && fused != null && random.nextBoolean()) {
          body.add(VirtualInstruction(fused, first.operand)); index += 2
        } else { body.add(first); index++ }
      }
      val end = block.terminator
      val mutated = if (end is VirtualTerminator.Branch && random.nextBoolean()) {
        val condition = if ((end.condition.ordinal - (if (end.condition.ordinal >= VirtualOperation.REFERENCE_EQUAL.ordinal)
          VirtualOperation.REFERENCE_EQUAL.ordinal else VirtualOperation.ZERO.ordinal)) % 2 == 0)
          VirtualOperation.entries[end.condition.ordinal + 1] else VirtualOperation.entries[end.condition.ordinal - 1]
        end.copy(condition = condition, trueTarget = end.falseTarget, falseTarget = end.trueTarget)
      } else end
      block.copy(instructions = body, terminator = mutated)
    }
    val instructions = lower(ordered)
    val handlers = handlers(ordered)
    val handlerOffset = layout.headerSize + instructions.size * layout.width
    val words = IntArray(handlerOffset + 1 + handlers.size * 4) { random.nextInt() }
    words[layout.handlersSlot] = handlerOffset xor layout.headerMask
    words[handlerOffset] = handlers.size
    handlers.forEachIndexed { index, handler ->
      val offset = handlerOffset + 1 + index * 4
      words[offset] = handler.start
      words[offset + 1] = handler.end
      words[offset + 2] = handler.target
      words[offset + 3] = handler.typeBridge
    }
    words[layout.localsSlot] = locals xor layout.headerMask
    words[layout.stackSlot] = stack xor layout.headerMask
    instructions.forEachIndexed { index, instruction ->
      val offset = layout.headerSize + index * layout.width
      words[offset + layout.operationSlot] = layout.codes.getValue(instruction.operation)
      words[offset + layout.operandSlot] = instruction.operand
      words[offset + layout.extraSlot] = instruction.extra
    }
    return words
  }

  companion object {
    fun fromInstructions(locals: Int, stack: Int, code: List<VirtualInstruction>,
      handlers: List<VirtualExceptionHandler> = emptyList()): VirtualProgram {
      require(code.isNotEmpty()) { "empty IR" }
      val starts = sortedSetOf(0)
      for (handler in handlers) {
        require(handler.start in code.indices && handler.end in (handler.start + 1)..code.size && handler.target in code.indices) {
          "invalid exception handler range"
        }
        starts += handler.start
        if (handler.end < code.size) starts += handler.end
        starts += handler.target
      }
      code.forEachIndexed { index, instruction ->
        if (instruction.operation.isBranch || instruction.operation == VirtualOperation.JUMP) starts.add(instruction.operand)
        if ((instruction.operation.isBranch || instruction.operation.isReturn || instruction.operation == VirtualOperation.JUMP)
          && index + 1 < code.size) starts.add(index + 1)
      }
      require(starts.all { it in code.indices }) { "branch target out of range" }
      val boundaries = starts.toList() + code.size
      val blocks = boundaries.zipWithNext().map { (start, end) ->
        val last = code[end - 1]
        val terminator = when (last.operation) {
          VirtualOperation.RETURN -> VirtualTerminator.Return
          VirtualOperation.RETURN_REFERENCE -> VirtualTerminator.ReturnReference
          VirtualOperation.RETURN_VOID -> VirtualTerminator.ReturnVoid
          VirtualOperation.RETURN_DOUBLE -> VirtualTerminator.ReturnDouble
          VirtualOperation.RETURN_LONG -> VirtualTerminator.ReturnLong
          VirtualOperation.RETURN_FLOAT -> VirtualTerminator.ReturnFloat
          VirtualOperation.THROW -> VirtualTerminator.Throw
          VirtualOperation.JUMP -> VirtualTerminator.Jump(last.operand)
          else -> if (last.operation.isBranch) VirtualTerminator.Branch(last.operation, last.operand, end)
            else VirtualTerminator.Jump(end)
        }
        val bodyEnd = if (last.operation.isBranch || last.operation.isReturn || last.operation == VirtualOperation.JUMP) end - 1 else end
        VirtualBlock(start, code.subList(start, bodyEnd), terminator)
      }
      require(blocks.all { block -> when (val end = block.terminator) {
        is VirtualTerminator.Jump -> end.target in starts
        is VirtualTerminator.Branch -> end.trueTarget in starts && end.falseTarget in starts
        VirtualTerminator.Return -> true
        VirtualTerminator.ReturnReference, VirtualTerminator.ReturnVoid, VirtualTerminator.ReturnDouble,
        VirtualTerminator.ReturnLong, VirtualTerminator.ReturnFloat, VirtualTerminator.Throw -> true
      } }) { "control flow falls outside program" }
      return VirtualProgram(locals, stack, blocks, exceptionHandlers = handlers)
    }
  }
}

internal class VirtualLayout(random: Random) {
  val width = 3 + random.nextInt(4)
  private val slots = (0 until width).shuffled(random)
  val operationSlot = slots[0]
  val operandSlot = slots[1]
  val extraSlot = slots[2]
  val codes = VirtualOperation.entries.zip((1..255).shuffled(random)).toMap()
  val headerSize = 4 + random.nextInt(6)
  private val headerSlots = (0 until headerSize).shuffled(random)
  val localsSlot = headerSlots[0]
  val stackSlot = headerSlots[1]
  val handlersSlot = headerSlots[2]
  val headerMask = random.nextInt()
  val keyPartA = random.nextInt()
  val keyPartB = random.nextInt()
  val increment = random.nextInt() or 1
  val rotation = 1 + random.nextInt(31)
  val streamVariant = random.nextInt(2)

  fun encrypt(words: IntArray): IntArray {
    var state = Integer.rotateLeft(keyPartA xor keyPartB, rotation)
    return IntArray(words.size) { index ->
      state = if (streamVariant == 0) Integer.rotateLeft(state + increment + index, rotation)
        else Integer.rotateRight(state xor (increment + index), rotation)
      val encrypted = words[index] xor state
      state = state xor words[index]
      encrypted
    }
  }

  fun constants(): List<Int> = listOf(width, operationSlot, operandSlot, extraSlot,
    keyPartA, keyPartB, increment, rotation, streamVariant, headerSize, localsSlot, stackSlot, headerMask, handlersSlot)
}
