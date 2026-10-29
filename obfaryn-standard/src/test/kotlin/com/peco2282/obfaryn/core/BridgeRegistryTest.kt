package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.transformer.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Random

class BridgeRegistryTest {
  private fun caller(name: String) = ClassNode().apply { this.name = name }
  private fun target(index: Int) = BridgeTarget.Invoke(Opcodes.INVOKEVIRTUAL,
    "fixture/symbols/DistinctiveBridgeOwner", "distinctiveHelperMethod$index", "(I)I", false)

  @Test fun idsAreSeededNonSequentialAndIndependentPerGroup() {
    fun ids(seed: Long): List<Int> {
      val registry = BridgeRegistry(Random(seed))
      val owner = caller("fixture/Caller")
      return (0 until 16).map { registry.register(owner, target(it)).id }
    }
    val expected = ids(42)
    assertEquals(expected, ids(42))
    assertNotEquals(expected, ids(43))
    assertEquals(expected.size, expected.toSet().size)
    assertFalse(expected.contains(0))
    assertFalse(expected.zipWithNext().any { (a, b) -> b == a + 1 })
    assertFalse(expected.any { it in 0 until expected.size })
    val first = BridgeRegistry(Random(42))
    val second = BridgeRegistry(Random(42))
    val owner = caller("fixture/Caller")
    val initial = first.register(owner, target(0))
    (1 until 16).forEach { first.register(owner, target(it)) }
    assertTrue(second.targets.isEmpty())
    assertEquals(initial.id, second.register(owner, target(0)).id)
    assertEquals(expected[1], second.register(owner, target(1)).id)
    assertEquals(16, first.targets.size)
    assertEquals(2, second.targets.size)
  }

  @Test fun deduplicationUsesBothCallerAndTarget() {
    val registry = BridgeRegistry(Random(42))
    val first = caller("fixture/First")
    val entry = registry.register(first, target(0))
    assertSame(entry, registry.register(first, target(0).copy()))
    assertSame(entry, registry.register(caller(first.name), target(0)))
    val otherCaller = registry.register(caller("fixture/Second"), target(0))
    val otherTarget = registry.register(first, target(1))
    assertEquals(3, registry.targets.size)
    assertEquals(3, setOf(entry.id, otherCaller.id, otherTarget.id).size)
    assertEquals(BridgeSignature(listOf(VirtualValueKind.REFERENCE, VirtualValueKind.INT), VirtualValueKind.INT), entry.signature)
  }

  @Test fun unencryptedProgramContainsOnlyBridgeIdNotTargetSymbols() {
    val registry = BridgeRegistry(Random(42))
    val target = target(0)
    val entry = registry.register(caller("fixture/Caller"), target)
    val program = VirtualProgram.fromInstructions(2, 2, listOf(
      VirtualInstruction(VirtualOperation.LOAD_REFERENCE, 0),
      VirtualInstruction(VirtualOperation.LOAD, 1),
      VirtualInstruction(VirtualOperation.INVOKE_BRIDGE, entry.id, (entry.signature.inputs.size shl 3) or 1),
      VirtualInstruction(VirtualOperation.RETURN)))
    program.bridgeSignatures = mapOf(entry.id to entry.signature)
    VirtualVerifier.verify(program, listOf(VirtualValueKind.REFERENCE, VirtualValueKind.INT), VirtualValueKind.INT)
    val invocation = program.instructions.single { it.operation == VirtualOperation.INVOKE_BRIDGE }
    assertEquals(entry.id, invocation.operand)
    assertEquals(17, invocation.extra)
    assertEquals(setOf(entry.id), program.bridgeSignatures.keys)
    val random = Random(43)
    val words = program.encode(VirtualLayout(random), random)
    val buffer = ByteBuffer.allocate(words.size * 4)
    words.forEach { buffer.putInt(it) }
    val blob = String(buffer.array(), StandardCharsets.ISO_8859_1)
    for (symbol in listOf(target.owner, target.name, target.descriptor)) {
      assertFalse(blob.contains(symbol), "unencrypted VM blob contains $symbol")
      assertFalse(program.instructions.toString().contains(symbol), "IR contains $symbol")
    }
    assertEquals(target, registry.targets.single().target)
  }
}
