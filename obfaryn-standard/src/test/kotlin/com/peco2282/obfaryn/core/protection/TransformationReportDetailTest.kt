package com.peco2282.obfaryn.core.protection

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.protection.BodyFeature
import com.peco2282.obfaryn.api.protection.TransformationBudget
import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.core.ObfuscationConfig
import com.peco2282.obfaryn.core.Obfuscator
import com.peco2282.obfaryn.core.fixtures.JavaJarFixture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.InsnNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Schema-version-2 transformation report: per-method growth percentage and the applied structural
 * budget, both in the in-memory [com.peco2282.obfaryn.api.transformer.TransformerReport] and in the
 * emitted JSON. Also guards the invariant that the report (which carries original names) is written
 * as a standalone file and is never bundled into the output JAR.
 */
class TransformationReportDetailTest {
  @TempDir lateinit var dir: Path

  private fun insertNops(count: Int) = object : Transformer {
    override val id = "grow"
    override val isolatedBodyFeature = BodyFeature.INTEGER_ARITHMETIC
    override fun transform(context: ObfuscationContext) {
      val method = context.classes.values.single().methods.single { it.name == "value" }
      repeat(count) { method.instructions.insert(InsnNode(Opcodes.NOP)) }
    }
  }

  @Test fun reportExposesGrowthPercentAndAppliedConstraintsAndIsNotBundled() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Main" to
      "package app; public class Main { public static int value() { return 7; } }"))
    val output = dir.resolve("out.jar")
    val reportPath = dir.resolve("report.json")
    // Distinctive, non-default limits so the applied budget cannot be confused with a fallback.
    val budget = TransformationBudget(maxInputInstructions = 100, maxInputBranches = 50,
      maxAddedInstructions = 1000, maxGrowthPercent = 500, maxOutputCodeBytes = 1234,
      growthAllowanceInstructions = 77)

    val result = Obfuscator(ObfuscationConfig(stringEncryption = false, numberObfuscation = false,
      renameClasses = false, renameMethods = false, renameFields = false, verifyOutput = true,
      transformationBudget = budget, transformationReport = reportPath),
      configurePipeline = { add(insertNops(3)) }).obfuscate(input, output)

    val row = result.transformerReports.single { it.id == "grow" }.methods
      .single { it.method == "app/Main.value()I" }
    assertEquals("applied", row.status)
    // `return 7` compiles to two instructions (bipush, ireturn); three NOPs make five.
    assertEquals(2, row.inputInstructions)
    assertEquals(5, row.outputInstructions)
    assertEquals(250, row.growthPercent) // 5 * 100 / 2
    val applied = requireNotNull(row.appliedBudget)
    assertEquals(1234, applied.maxOutputCodeBytes)
    assertEquals(500, applied.maxGrowthPercent)
    assertEquals(1000, applied.maxAddedInstructions)
    assertEquals(100, applied.maxInputInstructions)
    assertEquals(50, applied.maxInputBranches)
    assertEquals(77, applied.growthAllowanceInstructions)

    // A generated-helper / no-input row reports growthPercent -1 (nothing to grow from). Here the
    // only method has a body, so assert the documented sentinel is used for a zero-input case below.
    val json = String(Files.readAllBytes(reportPath), Charsets.UTF_8)
    assertTrue(json.contains("\"schemaVersion\":2"), json)
    assertTrue(json.contains("\"growthPercent\":250"), json)
    assertTrue(json.contains("\"appliedConstraints\":{\"maxInputInstructions\":100,\"maxInputBranches\":50," +
      "\"maxAddedInstructions\":1000,\"maxGrowthPercent\":500,\"growthAllowanceInstructions\":77," +
      "\"maxOutputCodeBytes\":1234}"), json)

    // Safety: the report is a standalone file and never an entry of the output JAR, and no class in
    // the JAR carries the report's marker text.
    JarFile(output.toFile()).use { jar ->
      val names = jar.entries().asSequence().map { it.name }.toList()
      assertEquals(listOf("app/Main.class"), names)
      for (entry in names) jar.getInputStream(jar.getJarEntry(entry)).use {
        assertFalse(String(it.readBytes(), Charsets.ISO_8859_1).contains("appliedConstraints"))
      }
    }
    assertTrue(Files.exists(reportPath))
  }

  @Test fun generatedHelperRowReportsGrowthPercentSentinel() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Main" to
      "package app; public class Main { public static int value() { return 7; } }"))
    val output = dir.resolve("helper.jar")
    val reportPath = dir.resolve("helper-report.json")
    val result = Obfuscator(ObfuscationConfig(stringEncryption = false, numberObfuscation = false,
      renameClasses = false, renameMethods = false, renameFields = false, verifyOutput = true,
      transformationReport = reportPath), configurePipeline = {
      add(object : Transformer {
        override val id = "inject"
        override val isolatedBodyFeature = BodyFeature.INTEGER_ARITHMETIC
        override fun transform(context: ObfuscationContext) {
          val owner = context.classes.values.single()
          owner.methods.single { it.name == "value" }.instructions.insert(InsnNode(Opcodes.NOP))
          context += org.objectweb.asm.tree.ClassNode().apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "generated/Helper", null, "java/lang/Object", null)
            methods.add(org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
              "value", "()I", null, null).apply {
              instructions.add(InsnNode(Opcodes.ICONST_5)); instructions.add(InsnNode(Opcodes.IRETURN)); maxStack = 1
            })
          }
        }
      })
    }).obfuscate(input, output)
    val helper = result.transformerReports.single { it.id == "inject" }.methods
      .single { it.method == "generated/Helper.value()I" }
    assertEquals("applied", helper.status)
    assertEquals(0, helper.inputInstructions)
    assertEquals(-1, helper.growthPercent) // no input body to grow from
    assertNotNull(helper.appliedBudget)
    assertTrue(String(Files.readAllBytes(reportPath), Charsets.UTF_8).contains("\"growthPercent\":-1"))
  }
}
