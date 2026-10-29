package com.peco2282.obfaryn.core.protection

import com.peco2282.obfaryn.api.protection.TransformationBudget
import com.peco2282.obfaryn.core.ObfuscationConfig
import com.peco2282.obfaryn.core.Obfuscator
import com.peco2282.obfaryn.core.fixtures.JavaJarFixture
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class KotlinLambdaBudgetTest {
  @TempDir lateinit var dir: Path

  @Test fun classAndIndyLambdasKeepStringsEncryptedWithBoundedGrowth() {
    val stdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI())
    for ((target, mode) in listOf("1.8" to "class", "21" to "indy")) {
      val work = Files.createDirectory(dir.resolve(mode))
      val source = work.resolve("Messages.kt")
      val bodies = (1..12).joinToString(",\n") {
        if (it % 2 == 0) "{ \"lambda-secret-$it\" }"
        else "{ \"lambda-secret-$it:\${prefix}:\${prefix.length}:\${prefix}\" }"
      }
      Files.write(source, """
        package sample
        class Messages {
          fun messages(prefix: String): String {
            val callbacks = listOf<() -> String>($bodies)
            return callbacks.joinToString("|") { it() }
          }
        }
      """.trimIndent().toByteArray())
      val classes = work.resolve("classes")
      val diagnostics = ByteArrayOutputStream()
      val exit = PrintStream(diagnostics).use {
        K2JVMCompiler().exec(it, "-no-stdlib", "-no-reflect", "-classpath", stdlib.path,
          "-jvm-target", target, "-Xlambdas=$mode", "-d", classes.toString(), source.toString())
      }
      assertEquals(ExitCode.OK, exit, diagnostics.toString())
      val input = work.resolve("input.jar")
      JarOutputStream(Files.newOutputStream(input)).use { jar ->
        Files.walk(classes).use { paths -> paths.filter { it.toString().endsWith(".class") }.forEach {
          jar.putNextEntry(JarEntry(classes.relativize(it).toString().replace('\\', '/')))
          jar.write(Files.readAllBytes(it)); jar.closeEntry()
        } }
      }
      // Both the default allowance and the explicitly strict ratio exercise real compiler output.
      for (allowance in listOf(TransformationBudget().growthAllowanceInstructions, 0)) {
        val output = work.resolve("out-$allowance.jar")
        val config = ObfuscationConfig(renameClasses = false, renameMethods = false, renameFields = false,
          numberObfuscation = false, applicationPackages = listOf("sample"), seed = 42,
          classpath = listOf(stdlib.toPath()), verifyOutput = true,
          transformationBudget = TransformationBudget(maxAddedInstructions = 256, maxGrowthPercent = 175,
            growthAllowanceInstructions = allowance), transformationReport = work.resolve("report-$allowance.json"))
        val result = Obfuscator(config).obfuscate(input, output)
        val strings = result.transformerReports.single { it.id == "string-encryption" }
        val literals = JavaJarFixture.classes(output).flatMap { it.methods }.flatMap {
          it.instructions.toArray().flatMap { insn -> when (insn) {
            is LdcInsnNode -> listOfNotNull(insn.cst as? String)
            is InvokeDynamicInsnNode -> insn.bsmArgs.filterIsInstance<String>()
            else -> emptyList()
          } }
        }
        if (allowance > 0) {
          assertFalse(strings.methods.any { it.status == "rejected" }, strings.methods.toString())
          assertTrue(result.stringsEncrypted >= 12)
          assertFalse(literals.any { it.contains("lambda-secret-") })
        } else {
          assertTrue(strings.methods.any { it.reason.contains("budget:growth-percent") })
          assertTrue(literals.any { it.contains("lambda-secret-") })
        }
        // The original application is absent from the parent loader and runtime classpath.
        URLClassLoader(arrayOf(output.toUri().toURL(), stdlib.toURI().toURL()), null).use { loader ->
          val cls = loader.loadClass("sample.Messages")
          val method = cls.getMethod("messages", String::class.java)
          for (prefix in listOf("test", "日本語", "")) repeat(2) {
            assertEquals((1..12).joinToString("|") { if (it % 2 == 0) "lambda-secret-$it" else "lambda-secret-$it:$prefix:${prefix.length}:$prefix" },
              method.invoke(cls.getConstructor().newInstance(), prefix))
          }
        }
      }
    }
  }
}
