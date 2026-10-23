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
import org.objectweb.asm.tree.*
import java.net.URLClassLoader
import java.nio.file.Path

class GeneratedHelperBudgetTest {
  @TempDir lateinit var dir: Path

  @Test fun oversizedCandidateRollsBackInsteadOfFailingDuringCodeComparison() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Main" to
      "package app; public class Main { public static int value() { return 7; } }"))
    val output = dir.resolve("oversized.jar")
    val result = Obfuscator(ObfuscationConfig(stringEncryption = false, numberObfuscation = false,
      renameClasses = false, renameMethods = false, renameFields = false, verifyOutput = true,
      transformationReport = dir.resolve("oversized.json")), configurePipeline = {
      add(object : Transformer {
        override val id = "oversized-candidate"
        override val isolatedBodyFeature = BodyFeature.INTEGER_ARITHMETIC
        override fun transform(context: ObfuscationContext) {
          val method = context.classes.values.single().methods.single { it.name == "value" }
          repeat(70000) { method.instructions.insert(InsnNode(Opcodes.NOP)) }
        }
      })
    }).obfuscate(input, output)
    val row = result.transformerReports.single { it.id == "oversized-candidate" }.methods
      .single { it.method == "app/Main.value()I" }
    assertEquals("rejected", row.status)
    assertTrue(row.reason.contains("budget:output-code-bytes"))
    assertEquals(row.inputCodeBytes, row.outputCodeBytes)
    URLClassLoader(arrayOf(output.toUri().toURL()), null).use { loader ->
      assertEquals(7, loader.loadClass("app.Main").getMethod("value").invoke(null))
    }
  }

  @Test fun injectedHelperUsesConfiguredCodeLimitAndIsReportedOnAcceptanceAndRollback() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Main" to
      "package app; public class Main { public static int value() { return 7; } }"))
    for (limit in listOf(16, 1000)) {
      val output = dir.resolve("helper-$limit.jar")
      val result = Obfuscator(ObfuscationConfig(stringEncryption = false, numberObfuscation = false,
        renameClasses = false, renameMethods = false, renameFields = false, verifyOutput = true,
        transformationBudget = TransformationBudget(maxOutputCodeBytes = limit),
        transformationReport = dir.resolve("helper-$limit.json")), configurePipeline = {
        add(object : Transformer {
          override val id = "inject-helper"
          override val isolatedBodyFeature = BodyFeature.INTEGER_ARITHMETIC
          override fun transform(context: ObfuscationContext) {
            val owner = context.classes.values.single()
            owner.methods.single { it.name == "value" }.instructions.insert(InsnNode(Opcodes.NOP))
            context += ClassNode().apply {
              visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "generated/Helper", null, "java/lang/Object", null)
              methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
                repeat(100) { instructions.add(InsnNode(Opcodes.NOP)) }
                instructions.add(InsnNode(Opcodes.ICONST_5))
                instructions.add(InsnNode(Opcodes.IRETURN))
                maxStack = 1
              })
            }
          }
        })
      }).obfuscate(input, output)
      val accepted = limit == 1000
      val report = result.transformerReports.single { it.id == "inject-helper" }
      val helper = report.methods.single { it.method == "generated/Helper.value()I" }
      assertEquals(if (accepted) "applied" else "rejected", helper.status)
      assertEquals(0, helper.inputCodeBytes)
      assertEquals(if (accepted) 102 else 0, helper.outputCodeBytes)
      assertEquals(if (accepted) 0L else 1L, report.metrics["budgetRejectedClasses"])
      if (!accepted) {
        assertTrue(helper.reason.contains("budget:generated-code-bytes:generated/Helper.value()I"))
        assertTrue(report.methods.any { it.method == "app/Main.value()I" && it.status == "rejected" })
      }
      val classes = JavaJarFixture.classes(output)
      assertEquals(accepted, classes.any { it.name == "generated/Helper" })
      assertEquals(accepted, classes.single { it.name == "app/Main" }.methods.single { it.name == "value" }
        .instructions.any { it.opcode == Opcodes.NOP })
      URLClassLoader(arrayOf(output.toUri().toURL()), null).use { loader ->
        assertEquals(7, loader.loadClass("app.Main").getMethod("value").invoke(null))
        if (accepted) assertEquals(5, loader.loadClass("generated.Helper").getMethod("value").invoke(null))
      }
    }
  }
}
