package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.transformer.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.*
import org.objectweb.asm.tree.*
import java.nio.ByteBuffer
import java.util.Base64
import java.util.Random

class TypedVirtualIrTest {
  private val int = VirtualValueKind.INT
  private val ref = VirtualValueKind.REFERENCE
  private fun instruction(op: VirtualOperation, operand: Int = 0, extra: Int = 0) = VirtualInstruction(op, operand, extra)
  private fun program(vararg code: VirtualInstruction) = VirtualProgram.fromInstructions(2, 4, code.toList())

  private fun run(program: VirtualProgram, method: String, ints: IntArray = intArrayOf(), refs: Array<Any?> = emptyArray()): Any? {
    val random = Random(73)
    val layout = VirtualLayout(random)
    val words = layout.encrypt(program.encode(layout, random))
    val bytes = ByteBuffer.allocate(words.size * 4)
    words.forEach { bytes.putInt(it) }
    val node = ClassNode()
    VirtualizationRuntime::class.java.getResourceAsStream("VirtualizationRuntime.class")!!.use {
      ClassReader(it).accept(node, 0)
    }
    val configuration = node.methods.single { it.name == "configuration" }
    configuration.instructions.clear()
    configuration.tryCatchBlocks.clear()
    configuration.localVariables?.clear()
    configuration.instructions.add(LdcInsnNode(layout.constants().size))
    configuration.instructions.add(IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT))
    layout.constants().forEachIndexed { index, value ->
      configuration.instructions.add(InsnNode(Opcodes.DUP))
      configuration.instructions.add(LdcInsnNode(index))
      configuration.instructions.add(LdcInsnNode(value))
      configuration.instructions.add(InsnNode(Opcodes.IASTORE))
    }
    configuration.instructions.add(VarInsnNode(Opcodes.ILOAD, 0))
    configuration.instructions.add(InsnNode(Opcodes.IALOAD))
    configuration.instructions.add(InsnNode(Opcodes.IRETURN))
    node.methods.forEach { m -> m.instructions.toArray().filterIsInstance<TableSwitchInsnNode>().forEach { switch ->
      if (switch.max == VirtualOperation.entries.lastIndex) {
        val pairs = VirtualOperation.entries.map { layout.codes.getValue(it) to switch.labels[it.ordinal] }.sortedBy { it.first }
        m.instructions.set(switch, LookupSwitchInsnNode(switch.dflt, pairs.map { it.first }.toIntArray(), pairs.map { it.second }.toTypedArray()))
      }
    } }
    val bridge = node.methods.single { it.name == "bridge" }
    bridge.instructions.clear()
    bridge.tryCatchBlocks.clear()
    bridge.localVariables?.clear()
    bridge.instructions.add(VarInsnNode(Opcodes.ALOAD, 1))
    val bridgeOutput = program.bridgeSignatures.values.singleOrNull()?.output
    if (bridgeOutput == int) {
      bridge.instructions.add(InsnNode(Opcodes.ICONST_0))
      bridge.instructions.add(InsnNode(Opcodes.AALOAD))
    }
    bridge.instructions.add(InsnNode(Opcodes.ARETURN))
    val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    node.accept(writer)
    val runtime = object : ClassLoader(javaClass.classLoader) {
      fun define(): Class<*> { val data = writer.toByteArray(); return defineClass(node.name.replace('/', '.'), data, 0, data.size) }
    }.define()
    return if (method == "execute") runtime.getMethod(method, String::class.java, IntArray::class.java)
      .invoke(null, Base64.getEncoder().encodeToString(bytes.array()), ints)
    else runtime.getMethod(method, String::class.java, IntArray::class.java, Array<Any>::class.java)
      .invoke(null, Base64.getEncoder().encodeToString(bytes.array()), ints, refs)
  }

  @Test fun mixedStackAndReferenceLocalsExecute() {
    val value = Any()
    val p = program(instruction(VirtualOperation.LOAD_REFERENCE, 0), instruction(VirtualOperation.DUPLICATE),
      instruction(VirtualOperation.STORE_REFERENCE, 1), instruction(VirtualOperation.CONSTANT, 9),
      instruction(VirtualOperation.SWAP), instruction(VirtualOperation.DROP), instruction(VirtualOperation.DROP),
      instruction(VirtualOperation.LOAD_REFERENCE, 1), instruction(VirtualOperation.RETURN_REFERENCE))
    VirtualVerifier.verify(p, listOf(ref), ref)
    assertSame(value, run(p, "executeReference", refs = arrayOf(value)))
    val legacy = program(instruction(VirtualOperation.LOAD), instruction(VirtualOperation.CONSTANT, 3),
      instruction(VirtualOperation.ADD), instruction(VirtualOperation.RETURN))
    VirtualVerifier.verify(legacy, 1)
    assertEquals(10, run(legacy, "execute", intArrayOf(7)))
    val void = program(instruction(VirtualOperation.RETURN_VOID))
    VirtualVerifier.verify(void, emptyList(), null)
    assertNull(run(void, "executeVoid"))
  }

  @Test fun referenceBranchesSurviveEncodingAndInversion() {
    for (op in listOf(VirtualOperation.REFERENCE_EQUAL, VirtualOperation.REFERENCE_NOT_EQUAL,
        VirtualOperation.REFERENCE_NULL, VirtualOperation.REFERENCE_NONNULL)) {
      val binary = op == VirtualOperation.REFERENCE_EQUAL || op == VirtualOperation.REFERENCE_NOT_EQUAL
      val code = mutableListOf(instruction(VirtualOperation.LOAD_REFERENCE))
      if (binary) code.add(instruction(VirtualOperation.PUSH_NULL))
      code.add(instruction(op, code.size + 3))
      code.add(instruction(VirtualOperation.CONSTANT, 0)); code.add(instruction(VirtualOperation.RETURN))
      code.add(instruction(VirtualOperation.CONSTANT, 1)); code.add(instruction(VirtualOperation.RETURN))
      val p = VirtualProgram.fromInstructions(1, 2, code)
      VirtualVerifier.verify(p, listOf(ref), int)
      val positive = op == VirtualOperation.REFERENCE_EQUAL || op == VirtualOperation.REFERENCE_NULL
      assertEquals(if (positive) 1 else 0, run(p, "executeInt", refs = arrayOf(null)))
      assertEquals(if (positive) 0 else 1, run(p, "executeInt", refs = arrayOf(Any())))
    }
  }

  @Test fun bridgePreservesArgumentOrderAndBoxing() {
    val value = Any()
    val p = program(instruction(VirtualOperation.CONSTANT, 42), instruction(VirtualOperation.LOAD_REFERENCE),
      instruction(VirtualOperation.INVOKE_BRIDGE, 7, 18), instruction(VirtualOperation.RETURN_REFERENCE))
    p.bridgeSignatures = mapOf(7 to BridgeSignature(listOf(int, ref), ref))
    VirtualVerifier.verify(p, listOf(ref), ref)
    assertArrayEquals(arrayOf(42, value), run(p, "executeReference", refs = arrayOf(value)) as Array<*>)
    val integer = program(instruction(VirtualOperation.CONSTANT, 17), instruction(VirtualOperation.INVOKE_BRIDGE, 2, 9),
      instruction(VirtualOperation.RETURN))
    integer.bridgeSignatures = mapOf(2 to BridgeSignature(listOf(int), int))
    VirtualVerifier.verify(integer, emptyList(), int)
    assertEquals(17, run(integer, "executeInt"))
    val void = program(instruction(VirtualOperation.INVOKE_BRIDGE, 4), instruction(VirtualOperation.RETURN_VOID))
    void.bridgeSignatures = mapOf(4 to BridgeSignature(emptyList(), null))
    VirtualVerifier.verify(void, emptyList(), null)
    assertNull(run(void, "executeVoid"))
  }

  @Test fun verifierRejectsIncompatibleStackJoinsAndPartiallyInitializedLocals() {
    val stackJoin = program(instruction(VirtualOperation.CONSTANT), instruction(VirtualOperation.ZERO, 4),
      instruction(VirtualOperation.CONSTANT), instruction(VirtualOperation.JUMP, 5),
      instruction(VirtualOperation.PUSH_NULL), instruction(VirtualOperation.DROP), instruction(VirtualOperation.RETURN_VOID))
    assertThrows(IllegalArgumentException::class.java) { VirtualVerifier.verify(stackJoin, emptyList(), null) }
    val localJoin = program(instruction(VirtualOperation.CONSTANT), instruction(VirtualOperation.ZERO, 5),
      instruction(VirtualOperation.CONSTANT), instruction(VirtualOperation.STORE), instruction(VirtualOperation.JUMP, 5),
      instruction(VirtualOperation.LOAD), instruction(VirtualOperation.RETURN))
    assertThrows(IllegalArgumentException::class.java) { VirtualVerifier.verify(localJoin, emptyList(), int) }
  }

  @Test fun verifierRejectsWrongKindsUninitializedLocalsReturnsAndBridges() {
    val invalid = listOf(
      program(instruction(VirtualOperation.PUSH_NULL), instruction(VirtualOperation.NEGATE), instruction(VirtualOperation.RETURN)),
      program(instruction(VirtualOperation.LOAD), instruction(VirtualOperation.RETURN)),
      program(instruction(VirtualOperation.PUSH_NULL), instruction(VirtualOperation.RETURN_REFERENCE)),
      program(instruction(VirtualOperation.RETURN_VOID)),
      program(instruction(VirtualOperation.INVOKE_BRIDGE, 3, 0), instruction(VirtualOperation.RETURN_VOID)))
    invalid.forEach { p -> assertThrows(IllegalArgumentException::class.java) { VirtualVerifier.verify(p, emptyList(), int) } }
    val p = program(instruction(VirtualOperation.PUSH_NULL), instruction(VirtualOperation.INVOKE_BRIDGE, 1, 9),
      instruction(VirtualOperation.RETURN))
    p.bridgeSignatures = mapOf(1 to BridgeSignature(listOf(int), int))
    assertThrows(IllegalArgumentException::class.java) { VirtualVerifier.verify(p, emptyList(), int) }
    assertEquals(38, VirtualOperation.MULTIPLY_CONSTANT.ordinal)
    assertEquals(48, VirtualOperation.INVOKE_BRIDGE.ordinal)
  }
}
