package com.peco2282.obfaryn.core.transformer

/**
 * Checks virtual IR before encoding or execution.
 *
 * Verification covers local and branch bounds, stack capacity and underflow, value kinds,
 * bridge signatures, and consistency at control-flow merge points.
 */
internal object VirtualVerifier {
  fun verify(program: VirtualProgram, arguments: Int) {
    require(arguments >= 0) { "invalid argument count" }
    verify(program, List(arguments) { VirtualValueKind.INT }, VirtualValueKind.INT)
  }

  fun verify(program: VirtualProgram, initialLocals: List<VirtualValueKind?>, returnKind: VirtualValueKind?) {
    require(program.locals >= initialLocals.size && program.stack >= 0) { "invalid local/stack capacity" }
    val code = program.instructions
    require(code.isNotEmpty()) { "empty IR" }
    for ((operation, operand, extra) in code) {
      if (operation in setOf(VirtualOperation.LOAD, VirtualOperation.STORE, VirtualOperation.INCREMENT,
          VirtualOperation.LOAD_REFERENCE, VirtualOperation.STORE_REFERENCE,
          VirtualOperation.LOAD_DOUBLE, VirtualOperation.STORE_DOUBLE,
          VirtualOperation.LOAD_LONG, VirtualOperation.STORE_LONG,
          VirtualOperation.LOAD_FLOAT, VirtualOperation.STORE_FLOAT))
        require(operand in 0 until program.locals) { "local index out of range" }
      if (operation.isBranch || operation == VirtualOperation.JUMP)
        require(operand in code.indices) { "branch target out of range" }
      if (operation == VirtualOperation.INVOKE_BRIDGE) {
        val signature = requireNotNull(program.bridgeSignatures[operand]) { "unknown bridge" }
        val kind = when (signature.output) {
          null -> 0; VirtualValueKind.INT -> 1; VirtualValueKind.REFERENCE -> 2
          VirtualValueKind.DOUBLE -> 3; VirtualValueKind.LONG -> 4
          VirtualValueKind.FLOAT -> 5
        }
        require(extra == (signature.inputs.size shl 3 or kind)) { "invalid bridge encoding" }
      }
    }
    data class State(val stack: List<VirtualValueKind>, val locals: List<VirtualValueKind?>)
    val states = arrayOfNulls<State>(code.size)
    val pending = java.util.ArrayDeque<Int>()
    states[0] = State(emptyList(), List(program.locals) { initialLocals.getOrNull(it) })
    pending.add(0)
    fun edge(target: Int, state: State) {
      require(target in code.indices) { "control flow falls outside program" }
      val old = states[target]
      if (old == null) { states[target] = state; pending.add(target) }
      else {
        require(old.stack == state.stack) { "inconsistent stack kinds at join" }
        val merged = old.locals.zip(state.locals).map { (a, b) -> if (a == b) a else null }
        if (merged != old.locals) { states[target] = State(old.stack, merged); pending.add(target) }
      }
    }
    while (pending.isNotEmpty()) {
      val index = pending.removeFirst()
      val instruction = code[index]
      val op = instruction.operation
      val state = states[index]!!
      val stack = state.stack.toMutableList()
      val locals = state.locals.toMutableList()
      fun pop(kind: VirtualValueKind? = null): VirtualValueKind {
        require(stack.isNotEmpty()) { "stack underflow at $index" }
        return stack.removeAt(stack.lastIndex).also { require(kind == null || it == kind) { "wrong stack kind at $index" } }
      }
      val int = VirtualValueKind.INT
      val ref = VirtualValueKind.REFERENCE
      val double = VirtualValueKind.DOUBLE
      val long = VirtualValueKind.LONG
      val float = VirtualValueKind.FLOAT
      when (op) {
        VirtualOperation.NOOP, VirtualOperation.JUMP, VirtualOperation.RETURN_VOID -> Unit
        VirtualOperation.CONSTANT -> stack.add(int)
        VirtualOperation.DOUBLE_CONSTANT -> stack.add(double)
        VirtualOperation.LONG_CONSTANT -> stack.add(long)
        VirtualOperation.FLOAT_CONSTANT -> stack.add(float)
        VirtualOperation.PUSH_NULL -> stack.add(ref)
        VirtualOperation.LOAD, VirtualOperation.LOAD_REFERENCE -> {
          val kind = if (op == VirtualOperation.LOAD) int else ref
          require(locals[instruction.operand] == kind) { "uninitialized or wrong local kind" }
          stack.add(kind)
        }
        VirtualOperation.STORE, VirtualOperation.STORE_REFERENCE -> {
          val kind = if (op == VirtualOperation.STORE) int else ref
          pop(kind); locals[instruction.operand] = kind
        }
        VirtualOperation.LOAD_DOUBLE -> {
          require(locals[instruction.operand] == double) { "uninitialized or wrong local kind" }
          stack.add(double)
        }
        VirtualOperation.STORE_DOUBLE -> {
          pop(double); locals[instruction.operand] = double
          if (instruction.operand + 1 < locals.size) locals[instruction.operand + 1] = null
        }
        VirtualOperation.LOAD_LONG -> {
          require(locals[instruction.operand] == long) { "uninitialized or wrong local kind" }
          stack.add(long)
        }
        VirtualOperation.STORE_LONG -> {
          pop(long); locals[instruction.operand] = long
          if (instruction.operand + 1 < locals.size) locals[instruction.operand + 1] = null
        }
        VirtualOperation.LOAD_FLOAT -> {
          require(locals[instruction.operand] == float) { "uninitialized or wrong local kind" }
          stack.add(float)
        }
        VirtualOperation.STORE_FLOAT -> { pop(float); locals[instruction.operand] = float }
        VirtualOperation.INCREMENT -> require(locals[instruction.operand] == int) { "wrong local kind" }
        VirtualOperation.DROP -> pop()
        VirtualOperation.DROP_DOUBLE -> require(pop() in setOf(double, long)) { "wrong stack kind at $index" }
        VirtualOperation.DUPLICATE -> { val kind = pop(); stack.add(kind); stack.add(kind) }
        VirtualOperation.DUPLICATE_DOUBLE -> {
          val kind = pop(); require(kind in setOf(double, long)) { "wrong stack kind at $index" }
          stack.add(kind); stack.add(kind)
        }
        VirtualOperation.SWAP -> { val a = pop(); val b = pop(); stack.add(a); stack.add(b) }
        in VirtualOperation.ADD..VirtualOperation.REMAINDER,
        in VirtualOperation.SHIFT_LEFT..VirtualOperation.XOR -> { pop(int); pop(int); stack.add(int) }
        VirtualOperation.NEGATE, VirtualOperation.BYTE, VirtualOperation.CHAR, VirtualOperation.SHORT,
        VirtualOperation.ADD_CONSTANT, VirtualOperation.MULTIPLY_CONSTANT -> { pop(int); stack.add(int) }
        in VirtualOperation.DOUBLE_ADD..VirtualOperation.DOUBLE_REMAINDER -> {
          pop(double); pop(double); stack.add(double)
        }
        VirtualOperation.DOUBLE_NEGATE -> { pop(double); stack.add(double) }
        VirtualOperation.DOUBLE_COMPARE_L, VirtualOperation.DOUBLE_COMPARE_G -> {
          pop(double); pop(double); stack.add(int)
        }
        VirtualOperation.INT_TO_DOUBLE -> { pop(int); stack.add(double) }
        VirtualOperation.DOUBLE_TO_INT -> { pop(double); stack.add(int) }
        in VirtualOperation.LONG_ADD..VirtualOperation.LONG_REMAINDER,
        in VirtualOperation.LONG_AND..VirtualOperation.LONG_XOR -> { pop(long); pop(long); stack.add(long) }
        VirtualOperation.LONG_NEGATE -> { pop(long); stack.add(long) }
        in VirtualOperation.LONG_SHIFT_LEFT..VirtualOperation.LONG_SHIFT_UNSIGNED -> {
          pop(int); pop(long); stack.add(long)
        }
        VirtualOperation.LONG_COMPARE -> { pop(long); pop(long); stack.add(int) }
        VirtualOperation.INT_TO_LONG -> { pop(int); stack.add(long) }
        VirtualOperation.LONG_TO_INT -> { pop(long); stack.add(int) }
        VirtualOperation.LONG_TO_DOUBLE -> { pop(long); stack.add(double) }
        VirtualOperation.DOUBLE_TO_LONG -> { pop(double); stack.add(long) }
        VirtualOperation.ARRAY_LENGTH -> { pop(ref); stack.add(int) }
        VirtualOperation.INT_ARRAY_LOAD, VirtualOperation.BYTE_ARRAY_LOAD,
        VirtualOperation.CHAR_ARRAY_LOAD, VirtualOperation.SHORT_ARRAY_LOAD -> {
          pop(int); pop(ref); stack.add(int)
        }
        VirtualOperation.LONG_ARRAY_LOAD -> { pop(int); pop(ref); stack.add(long) }
        VirtualOperation.DOUBLE_ARRAY_LOAD -> { pop(int); pop(ref); stack.add(double) }
        VirtualOperation.REFERENCE_ARRAY_LOAD -> { pop(int); pop(ref); stack.add(ref) }
        VirtualOperation.INT_ARRAY_STORE, VirtualOperation.BYTE_ARRAY_STORE,
        VirtualOperation.CHAR_ARRAY_STORE, VirtualOperation.SHORT_ARRAY_STORE -> {
          pop(int); pop(int); pop(ref)
        }
        VirtualOperation.LONG_ARRAY_STORE -> { pop(long); pop(int); pop(ref) }
        VirtualOperation.DOUBLE_ARRAY_STORE -> { pop(double); pop(int); pop(ref) }
        VirtualOperation.REFERENCE_ARRAY_STORE -> { pop(ref); pop(int); pop(ref) }
        in VirtualOperation.FLOAT_ADD..VirtualOperation.FLOAT_REMAINDER -> {
          pop(float); pop(float); stack.add(float)
        }
        VirtualOperation.FLOAT_NEGATE -> { pop(float); stack.add(float) }
        VirtualOperation.FLOAT_COMPARE_L, VirtualOperation.FLOAT_COMPARE_G -> {
          pop(float); pop(float); stack.add(int)
        }
        VirtualOperation.INT_TO_FLOAT -> { pop(int); stack.add(float) }
        VirtualOperation.FLOAT_TO_INT -> { pop(float); stack.add(int) }
        VirtualOperation.LONG_TO_FLOAT -> { pop(long); stack.add(float) }
        VirtualOperation.FLOAT_TO_LONG -> { pop(float); stack.add(long) }
        VirtualOperation.FLOAT_TO_DOUBLE -> { pop(float); stack.add(double) }
        VirtualOperation.DOUBLE_TO_FLOAT -> { pop(double); stack.add(float) }
        VirtualOperation.FLOAT_ARRAY_LOAD -> { pop(int); pop(ref); stack.add(float) }
        VirtualOperation.FLOAT_ARRAY_STORE -> { pop(float); pop(int); pop(ref) }
        in VirtualOperation.ZERO..VirtualOperation.NONPOSITIVE -> pop(int)
        in VirtualOperation.EQUAL..VirtualOperation.LESS_EQUAL -> { pop(int); pop(int) }
        VirtualOperation.REFERENCE_EQUAL, VirtualOperation.REFERENCE_NOT_EQUAL -> { pop(ref); pop(ref) }
        VirtualOperation.REFERENCE_NULL, VirtualOperation.REFERENCE_NONNULL -> pop(ref)
        VirtualOperation.RETURN -> { require(returnKind == int) { "wrong return kind" }; pop(int) }
        VirtualOperation.RETURN_REFERENCE -> { require(returnKind == ref) { "wrong return kind" }; pop(ref) }
        VirtualOperation.RETURN_DOUBLE -> { require(returnKind == double) { "wrong return kind" }; pop(double) }
        VirtualOperation.RETURN_LONG -> { require(returnKind == long) { "wrong return kind" }; pop(long) }
        VirtualOperation.RETURN_FLOAT -> { require(returnKind == float) { "wrong return kind" }; pop(float) }
        VirtualOperation.THROW -> pop(ref)
        VirtualOperation.INVOKE_BRIDGE -> {
          val signature = program.bridgeSignatures.getValue(instruction.operand)
          signature.inputs.asReversed().forEach { pop(it) }
          signature.output?.let { stack.add(it) }
        }
        else -> error("unsupported operation")
      }
      require(stack.size <= program.stack) { "stack capacity exceeded at $index" }
      val next = State(stack.toList(), locals.toList())
      when {
        op.isReturn -> {
          require(op != VirtualOperation.RETURN_VOID || returnKind == null) { "wrong return kind" }
          require(stack.isEmpty()) { "nonempty stack at return" }
        }
        op == VirtualOperation.JUMP -> edge(instruction.operand, next)
        op.isBranch -> { edge(instruction.operand, next); edge(index + 1, next) }
        else -> edge(index + 1, next)
      }
    }
  }
}
