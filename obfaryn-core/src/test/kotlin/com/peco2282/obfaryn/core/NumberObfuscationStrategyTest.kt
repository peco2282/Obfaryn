package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.transformer.NumberObfuscationStrategy
import com.peco2282.obfaryn.api.transformer.NumberObfuscationSelector
import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.transformer.NumberObfuscationTransformer
import com.peco2282.obfaryn.transformer.builder.transformers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.util.Random

class NumberObfuscationStrategyTest {
  private val values = listOf<Number>(
    -1, 0, 1, 2, Int.MIN_VALUE, Int.MAX_VALUE, 128, 32768,
    -1L, 0L, 1L, Long.MIN_VALUE, Long.MAX_VALUE,
    0f, -0f, Float.POSITIVE_INFINITY, Float.fromBits(0x7fc01234),
    0.0, -0.0, Double.NEGATIVE_INFINITY, Double.fromBits(0x7ff8000000001234L),
  )

  private fun owner() = ClassNode().apply {
    name = "app/Numbers"
    version = Opcodes.V1_8
    access = Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER
    superName = "java/lang/Object"
    values.forEachIndexed { index, value ->
      val descriptor = when (value) { is Int -> "I"; is Long -> "J"; is Float -> "F"; else -> "D" }
      methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value$index", "()$descriptor", null, null).apply {
        instructions.add(when {
          value is Int && value in -1..5 -> InsnNode(Opcodes.ICONST_0 + value)
          value is Int && value == 128 -> IntInsnNode(Opcodes.SIPUSH, value)
          value is Long && value in 0L..1L -> InsnNode(Opcodes.LCONST_0 + value.toInt())
          else -> LdcInsnNode(value)
        })
        instructions.add(InsnNode(when (value) { is Int -> Opcodes.IRETURN; is Long -> Opcodes.LRETURN; is Float -> Opcodes.FRETURN; else -> Opcodes.DRETURN }))
        maxStack = 2
      })
    }
  }

  private fun context(node: ClassNode) = ObfuscationContext(linkedMapOf(node.name to node), linkedMapOf())

  private fun assertRuntime(node: ClassNode) {
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    node.accept(writer)
    val bytes = writer.toByteArray()
    val loaded = object : ClassLoader(null) {
      fun output() = defineClass(null, bytes, 0, bytes.size)
    }.output()
    values.forEachIndexed { index, expected ->
      val actual = loaded.getMethod("value$index").invoke(null)
      when (expected) {
        is Float -> assertEquals(expected.toRawBits(), (actual as Float).toRawBits())
        is Double -> assertEquals(expected.toRawBits(), (actual as Double).toRawBits())
        else -> assertEquals(expected, actual)
      }
    }
  }

  @Test fun `default expressions preserve all numeric types and raw bits`() {
    repeat(12) { seed ->
      val node = owner()
      val pass = NumberObfuscationTransformer(Random(seed.toLong()))
      pass.transform(context(node))
      assertEquals(values.size - 6, pass.count)
      assertRuntime(node)
    }
  }

  @Test fun `builder strategy can select sites including small integers`() {
    val node = owner()
    val context = context(node)
    val random = Random(10)
    var calls = 0
    val pass = transformers {
      number {
        includeConstantFields = false
        this.random = random
        selector = NumberObfuscationSelector { site, source ->
          calls++
          assertSame(context, site.context)
          assertSame(node, site.owner)
          assertSame(random, source)
          assertTrue(site.method.instructions.contains(site.instruction))
          site.value == 0
        }
        strategy = object : NumberObfuscationStrategy {
          override fun generateInt(value: Int, random: Random) = InsnList().apply {
            assertEquals(0, value)
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.ISUB))
          }
          override fun generateLong(value: Long, random: Random): InsnList = error("unselected")
        }
      }
    }.single() as NumberObfuscationTransformer
    pass.transform(context)
    assertEquals(values.size, calls)
    assertEquals(1, pass.count)
    assertRuntime(node)
  }

  @Test fun `empty expressions are rejected before removing the constant`() {
    val node = owner()
    val original = node.methods.first().instructions.first
    val pass = NumberObfuscationTransformer(Random(1), object : NumberObfuscationStrategy {
      override fun generateInt(value: Int, random: Random) = InsnList()
      override fun generateLong(value: Long, random: Random) = InsnList()
    }, NumberObfuscationSelector { _, _ -> true })
    assertThrows(IllegalArgumentException::class.java) { pass.transform(context(node)) }
    assertSame(original, node.methods.first().instructions.first)
    assertEquals(0, pass.count)
  }

  @Test fun `body policy takes precedence over custom strategies`() {
    val node = owner().apply {
      invisibleAnnotations = mutableListOf(AnnotationNode("Lcom/peco2282/obfaryn/annotations/NoObfuscation;"))
    }
    NumberObfuscationTransformer(Random(1), selector = NumberObfuscationSelector { _, _ -> error("policy bypassed") })
      .transform(context(node))
    assertRuntime(node)
  }

  @Test fun `custom integer expressions also handle floating point raw bits`() {
    val node = owner()
    var ints = 0
    var longs = 0
    val strategy = object : NumberObfuscationStrategy {
      override fun generateInt(value: Int, random: Random) = InsnList().apply {
        ints++
        add(LdcInsnNode(value.inv()))
        add(InsnNode(Opcodes.ICONST_M1))
        add(InsnNode(Opcodes.IXOR))
      }
      override fun generateLong(value: Long, random: Random) = InsnList().apply {
        longs++
        add(LdcInsnNode(value.inv()))
        add(LdcInsnNode(-1L))
        add(InsnNode(Opcodes.LXOR))
      }
    }
    val pass = NumberObfuscationTransformer(Random(2), strategy, NumberObfuscationSelector { _, _ -> true })
    pass.transform(context(node))
    assertEquals(values.count { it is Int || it is Float }, ints)
    assertEquals(values.count { it is Long || it is Double }, longs)
    assertEquals(values.size, pass.count)
    assertRuntime(node)
  }

  @Test fun `typed generators can skip constants or override floating point generation`() {
    val node = owner()
    var floats = 0
    var doubles = 0
    val strategy = object : NumberObfuscationStrategy {
      override fun generateInt(value: Int, random: Random): InsnList? = null
      override fun generateLong(value: Long, random: Random): InsnList? = null
      override fun generateFloat(value: Float, random: Random) = InsnList().apply {
        floats++
        add(LdcInsnNode(value))
      }
      override fun generateDouble(value: Double, random: Random) = InsnList().apply {
        doubles++
        add(LdcInsnNode(value))
      }
    }
    val pass = NumberObfuscationTransformer(Random(2), strategy, NumberObfuscationSelector { _, _ -> true })
    pass.transform(context(node))
    assertEquals(values.count { it is Float }, floats)
    assertEquals(values.count { it is Double }, doubles)
    assertEquals(floats + doubles, pass.count)
    assertRuntime(node)
  }
}