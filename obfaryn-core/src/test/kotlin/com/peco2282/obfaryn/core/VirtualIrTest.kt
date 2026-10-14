package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.transformer.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Random

class VirtualIrTest {
  @Test fun verifierRejectsInvalidStackLocalsAndBranches() {
    fun verify(vararg code: VirtualInstruction) = VirtualVerifier.verify(
      VirtualProgram.fromInstructions(1, 2, code.toList()), 1)
    assertThrows(IllegalArgumentException::class.java) { verify(VirtualInstruction(VirtualOperation.RETURN)) }
    assertThrows(IllegalArgumentException::class.java) {
      verify(VirtualInstruction(VirtualOperation.LOAD, 1), VirtualInstruction(VirtualOperation.RETURN))
    }
    assertThrows(IllegalArgumentException::class.java) { verify(VirtualInstruction(VirtualOperation.JUMP, 50)) }
    assertThrows(IllegalArgumentException::class.java) {
      verify(VirtualInstruction(VirtualOperation.CONSTANT, 1), VirtualInstruction(VirtualOperation.ZERO, 3),
        VirtualInstruction(VirtualOperation.CONSTANT, 2), VirtualInstruction(VirtualOperation.CONSTANT, 3),
        VirtualInstruction(VirtualOperation.RETURN))
    }
    assertThrows(IllegalArgumentException::class.java) {
      verify(VirtualInstruction(VirtualOperation.CONSTANT, 1), VirtualInstruction(VirtualOperation.DUPLICATE),
        VirtualInstruction(VirtualOperation.DUPLICATE), VirtualInstruction(VirtualOperation.RETURN))
    }
  }

  @Test fun blocksShuffleFuseAndRandomPaddingFeedsTheKeyStream() {
    val code = listOf(VirtualInstruction(VirtualOperation.LOAD, 0), VirtualInstruction(VirtualOperation.ZERO, 6),
      VirtualInstruction(VirtualOperation.LOAD, 0), VirtualInstruction(VirtualOperation.CONSTANT, 3),
      VirtualInstruction(VirtualOperation.MULTIPLY), VirtualInstruction(VirtualOperation.RETURN),
      VirtualInstruction(VirtualOperation.CONSTANT, 7), VirtualInstruction(VirtualOperation.RETURN))
    val program = VirtualProgram.fromInstructions(1, 2, code)
    VirtualVerifier.verify(program, 1)
    assertEquals(3, program.blocks.size)
    val outputs = (0L..30L).map { seed ->
      val random = Random(seed)
      val layout = VirtualLayout(random)
      val words = program.encode(layout, random)
      val used = setOf(layout.operationSlot, layout.operandSlot, layout.extraSlot)
      if (layout.width > 3) {
        val filler = (0 until layout.width).first { it !in used }
        val index = layout.headerSize + filler
        val altered = words.copyOf().also { it[index] = it[index] xor 1 }
        assertNotEquals(layout.encrypt(words)[index + 1], layout.encrypt(altered)[index + 1])
        assertNotEquals(0, words[index])
      }
      val operations = (layout.headerSize until words.size step layout.width).map {
        layout.codes.entries.single { entry -> entry.value == words[it + layout.operationSlot] }.key
      }
      words.toList() to operations
    }
    assertTrue(outputs.map { it.first }.toSet().size > 1)
    assertTrue(outputs.any { VirtualOperation.MULTIPLY_CONSTANT in it.second })
    assertTrue(outputs.any { VirtualOperation.MULTIPLY in it.second })
  }
}