package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.transformer.Transformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TransformerPipelineTest {
  @org.junit.jupiter.api.io.TempDir lateinit var dir: java.nio.file.Path

  @Test fun `configuration and custom pass reports work together`() {
    val input = dir.resolve("input.jar")
    java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(input)).use { }
    var executed = false
    val custom = object : Transformer {
      override val id = "custom"
      override fun transform(context: ObfuscationContext) { executed = true }
      override fun report() = com.peco2282.obfaryn.api.transformer.TransformerReport(
        id, mapOf("visited" to 7L), listOf("custom warning"),
      )
    }
    val result = Obfuscator(
      config = ObfuscationConfig.lightweight().copy(dryRun = true),
      configurePipeline = { disable("rename"); add(custom) },
    ).obfuscate(input, dir.resolve("output.jar"))
    assertTrue(executed)
    assertEquals(7L, result.transformerReports.single { it.id == "custom" }.metrics["visited"])
    assertTrue("custom warning" in result.warnings)
    assertEquals(0, result.stringsEncrypted)
    assertFalse(java.nio.file.Files.exists(dir.resolve("output.jar")))
  }

  private fun pass(name: String, priority: Int = 0, dependencies: Set<String> = emptySet()) = object : Transformer {
    override val id = name
    override val order = priority
    override val requires = dependencies
    override fun transform(context: ObfuscationContext) = Unit
  }

  @Test fun `dependencies override numeric order and replacements are supported`() {
    val replacement = pass("first", 100)
    val pipeline = TransformerPipeline(listOf(pass("first"), pass("second", -10, setOf("first"))))
    pipeline.replace("first", replacement)
    assertEquals(listOf(replacement.id, "second"), pipeline.ordered().map { it.id })
  }

  @Test fun `invalid graphs and edits fail before running`() {
    assertThrows(IllegalArgumentException::class.java) {
      TransformerPipeline(listOf(pass("a", dependencies = setOf("missing")))).ordered()
    }
    assertThrows(IllegalArgumentException::class.java) {
      TransformerPipeline(listOf(pass("a", dependencies = setOf("b")), pass("b", dependencies = setOf("a")))).ordered()
    }
    val pipeline = TransformerPipeline(listOf(pass("a")))
    assertThrows(IllegalArgumentException::class.java) { pipeline.add(pass("a")) }
    assertThrows(IllegalArgumentException::class.java) { pipeline.disable("missing") }
    pipeline.disable("a")
    assertTrue(pipeline.ordered().isEmpty())
  }
}
