package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.transformer.TransformationInputs
import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.transformer.StringEncryptionStrategy
import com.peco2282.obfaryn.transformer.XorStringEncryptionStrategy
import com.peco2282.obfaryn.transformer.builder.StringBuilder
import com.peco2282.obfaryn.transformer.builder.TransformersBuilder
import com.peco2282.obfaryn.transformer.builder.transformers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import java.util.Random

class TransformersBuilderTest {
  private fun owner() = ClassNode().apply {
    name = "app/Test"
    version = Opcodes.V1_8
    fields.add(FieldNode(Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "text", "Ljava/lang/String;", null, "hello"))
    fields.add(FieldNode(Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "number", "I", null, 42))
  }

  @Test fun `combined options generate one preprocessor without accumulating on build`() {
    val builder = TransformersBuilder().apply { number(); string() }
    repeat(2) {
      val passes = builder.build()
      assertEquals(listOf("constant-fields", "number-obfuscation", "string-encryption"), passes.map { it.id })
      val node = owner()
      val context = ObfuscationContext(linkedMapOf(node.name to node), linkedMapOf())
      context.transformationInputs = object : TransformationInputs {
        override fun isApplicationClass(internalName: String) = true
      }
      passes.first().transform(context)
      assertTrue(node.fields.all { it.value == null })
      assertEquals(1, node.methods.count { it.name == "<clinit>" })
    }
  }

  @Test fun `constant inclusion follows each pass and uses configured strategy snapshot`() {
    val seen = mutableListOf<String>()
    var captured: StringBuilder? = null
    val passes = transformers {
      string {
        captured = this
        random = Random(10)
        strategy = object : StringEncryptionStrategy {
          override fun encrypt(value: String, key: Int) = XorStringEncryptionStrategy().encrypt(value, key).also { seen += value }
        }
      }
      number { includeConstantFields = false; random = Random(20) }
    }
    captured!!.includeConstantFields = false
    captured!!.strategy = object : StringEncryptionStrategy {
      override fun encrypt(value: String, key: Int) = error("mutated builder was retained")
    }
    val node = owner()
    val context = ObfuscationContext(linkedMapOf(node.name to node), linkedMapOf())
    context.transformationInputs = object : TransformationInputs {
      override fun isApplicationClass(internalName: String) = true
    }
    passes.sortedBy { it.order }.forEach { it.transform(context) }
    assertNull(node.fields[0].value)
    assertEquals(42, node.fields[1].value)
    assertEquals(listOf("hello"), seen)
  }

  @Test fun `disabled absent and removed passes do not request constant conversion`() {
    assertTrue(transformers {}.isEmpty())
    assertEquals(listOf("string-encryption", "number-obfuscation"), transformers {
      string { includeConstantFields = false }
      number { includeConstantFields = false }
    }.map { it.id })
    val builder = TransformersBuilder().apply { number() }
    builder -= builder.build().last()
    assertTrue(builder.build().isEmpty())
    val numberOnly = transformers { number() }
    val node = owner()
    val context = ObfuscationContext(linkedMapOf(node.name to node), linkedMapOf())
    context.transformationInputs = object : TransformationInputs {
      override fun isApplicationClass(internalName: String) = true
    }
    numberOnly.first().transform(context)
    assertEquals("hello", node.fields[0].value)
    assertNull(node.fields[1].value)
  }

  @Test fun `duplicate configuration and manual preprocessor conflicts are rejected`() {
    assertThrows(IllegalArgumentException::class.java) { transformers { string(); string() } }
    assertThrows(IllegalArgumentException::class.java) { transformers { number(); number() } }
    assertThrows(IllegalArgumentException::class.java) {
      transformers { string(); this += com.peco2282.obfaryn.transformer.ConstantFieldTransformer() }
    }
  }
}