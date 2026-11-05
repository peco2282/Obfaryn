package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.*
import com.peco2282.obfaryn.api.assembly.*
import com.peco2282.obfaryn.api.protection.*
import com.peco2282.obfaryn.api.transformer.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.*
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.*
import java.net.URLClassLoader
import java.nio.file.*
import java.util.jar.*

class AssemblyTest {
  @TempDir lateinit var dir: Path

  private fun input(): Path {
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    writer.visit(52, Opcodes.ACC_PUBLIC, "example/Main", null, "java/lang/Object", null)
    writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "answer", "()I", null, null).apply {
      visitCode(); visitIntInsn(Opcodes.BIPUSH, 7); visitInsn(Opcodes.IRETURN); visitMaxs(0, 0); visitEnd()
    }
    writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "identity", "(Lexample/Main;)Lexample/Main;", null, null).apply {
      visitCode(); visitVarInsn(Opcodes.ALOAD, 0); visitInsn(Opcodes.ARETURN); visitMaxs(0, 0); visitEnd()
    }
    writer.visitEnd()
    return dir.resolve("input.jar").also { path ->
      JarOutputStream(Files.newOutputStream(path)).use { jar ->
        jar.putNextEntry(JarEntry("example/Main.class")); jar.write(writer.toByteArray()); jar.closeEntry()
      }
    }
  }

  private fun answer(path: Path, owner: String = "example.Main", method: String = "answer"): Any? =
    URLClassLoader(arrayOf(path.toUri().toURL()), null).use { loader ->
      loader.loadClass(owner).getMethod(method).invoke(null)
    }

  @Test fun `core runs with no standard implementation or default transformations`() {
    assertThrows(ClassNotFoundException::class.java) { Class.forName("com.peco2282.obfaryn.core.Obfuscator") }
    assertThrows(ClassNotFoundException::class.java) { Class.forName("com.peco2282.obfaryn.transformer.StringEncryptionTransformer") }
    val output = dir.resolve("output.jar")
    val result = TransformationEngine().transform(input(), output)
    assertTrue(result.transformerReports.isEmpty())
    assertEquals(7, answer(output))
  }

  @Test fun `API-only package is explicit fresh per run and participates in budgets and custom policy`() {
    val source = input()
    val recipes = TransformationPackages.select(listOf("example-padding"))
    val engine = TransformationEngine(EngineOptions(seed = 12))
    repeat(2) { run ->
      val output = dir.resolve("pass-$run.jar")
      val result = engine.transform(source, output, recipes)
      assertEquals(1L, result.transformerReports.single().metrics["transformed"])
      assertEquals(7, answer(output))
    }
    val rejected = TransformationEngine(EngineOptions(budget = TransformationBudget(maxAddedInstructions = 0)))
      .transform(source, dir.resolve("rejected.jar"), recipes)
    assertEquals(0L, rejected.transformerReports.single().metrics["transformed"])
    assertTrue(rejected.transformerReports.single().methods.any { it.status == "rejected" })
    val denied = TransformationEngine(EngineOptions(protectionRules = listOf(
      ProtectionRule("example/.*", mapOf(BodyFeature("example:padding") to false)))))
      .transform(source, dir.resolve("denied.jar"), recipes)
    assertEquals(0L, denied.transformerReports.single().metrics["transformed"])
    assertEquals(7, answer(dir.resolve("denied.jar")))
  }

  @Test fun `platform packages compose after user recipes and before final edits`() {
    val selected = example.extension.ExamplePlatformProvider().create()
    val order = mutableListOf<String>()
    val recipe = TransformationPackage { _, registry ->
      order += "package"
      registry.add(object : Transformer {
        override val id = "user"
        override fun transform(context: ObfuscationContext) { order += "run" }
      })
    }
    val result = TransformationEngine(EngineOptions(additionalPlatforms = listOf(selected)))
      .transform(input(), dir.resolve("platform.jar"), listOf(recipe), listOf(PipelineConfigurer {
        assertNotNull(this["example-padding"])
        order += "edit"
      }))
    assertEquals(listOf("package", "edit", "run"), order)
    assertEquals(listOf("generic", "example"), result.platforms)
    assertTrue(result.transformerReports.any { it.id == "example-padding" })
    assertThrows(IllegalArgumentException::class.java) {
      TransformationEngine(EngineOptions(additionalPlatforms = listOf(selected, selected)))
        .transform(input(), dir.resolve("duplicate.jar"))
    }
  }

  @Test fun `third-party renamer preserves original identity even when methods are reordered`() {
    val seen = mutableListOf<String>()
    val recipe = TransformationPackage { _, registry ->
      registry.add(object : RenamingTransformer {
        override val id = "my-rename"
        override val classMapping = mapOf("example/Main" to "renamed/Entry")
        override val methodMapping = mapOf(MemberKey("example/Main", "answer", "()I") to "a")
        override val fieldMapping = emptyMap<MemberKey, String>()
        override fun transform(context: ObfuscationContext) {
          val mapper = object : Remapper() {
            override fun map(name: String) = classMapping[name] ?: name
            override fun mapMethodName(owner: String, name: String, desc: String) = methodMapping[MemberKey(owner, name, desc)] ?: name
          }
          for (node in context.classes.values.toList()) {
            val mapped = ClassNode(); node.accept(ClassRemapper(mapped, mapper))
            mapped.methods.reverse()
            context.removeClass(node.name); context.putClass(mapped)
          }
        }
      })
      registry.add(object : Transformer {
        override val id = "inspect"
        override val requires = setOf("my-rename")
        override fun transform(context: ObfuscationContext) {
          for (owner in context.classes.values) for (method in owner.methods)
            seen += context.transformationInputs.methodIdentity(owner, method)
          assertTrue(context.transformationInputs.isApplicationClass("renamed/Entry"))
        }
      })
    }
    val result = TransformationEngine(EngineOptions(applicationPackages = listOf("example")))
      .transform(input(), dir.resolve("renamed.jar"), listOf(recipe))
    assertEquals(setOf("example/Main.answer()I", "example/Main.identity(Lexample/Main;)Lexample/Main;"), seen.toSet())
    assertTrue(result.mapping.contains("example/Main.answer()I -> a"))
    assertEquals(7, answer(result.output, "renamed.Entry", "a"))
  }

  @Test fun `lifecycle hooks run in order and dry runs never finalize output`() {
    val events = mutableListOf<String>()
    val recipe = TransformationPackage { assembly, _ ->
      assembly.outputProcessor(object : OutputProcessor {
        override fun beforeWrite(context: ObfuscationContext) { events += "before" }
        override fun afterWrite(output: Path) { assertTrue(Files.exists(output)); events += "after" }
      })
    }
    val source = input()
    TransformationEngine().transform(source, dir.resolve("hooks.jar"), listOf(recipe))
    TransformationEngine(EngineOptions(dryRun = true)).transform(source, dir.resolve("dry.jar"), listOf(recipe))
    assertEquals(listOf("before", "after", "before"), events)
    assertFalse(Files.exists(dir.resolve("dry.jar")))
  }
  @Test fun `API-only renamers can read live platform and user name rules`() {
    val inspect = TransformationPackage { _, registry ->
      registry.add(object : Transformer {
        override val id = "inspect-names"
        override fun transform(context: ObfuscationContext) {
          val owner = context.classes.getValue("example/Main")
          assertTrue(context.namePreservation.keepsClassName(owner))
          assertTrue(context.namePreservation.keepsMethodName(owner, owner.methods.single { it.name == "answer" }))
          assertFalse(context.namePreservation.keepsMethodName(owner, owner.methods.single { it.name == "identity" }))
          assertTrue(context.namePreservation.keepsResourceName("config.properties"))
        }
      })
    }
    val lateRules = TransformationPackage { assembly, _ ->
      assembly.configuration.keepClassNameOnly("example.Main").keepMethod("example.Main", "answer", "()I")
        .keepResource("config.properties")
    }
    TransformationEngine().transform(input(), dir.resolve("names.jar"), listOf(inspect, lateRules))
  }

  @Test fun `package inputs cannot be overwritten and rejection preserves existing destination`() {
    val output = dir.resolve("secret.key")
    Files.write(output, byteArrayOf(1, 2, 3))
    val recipe = TransformationPackage { assembly, _ -> assembly.protectInput(output) }
    assertThrows(IllegalArgumentException::class.java) {
      TransformationEngine().transform(input(), output, listOf(recipe))
    }
    assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(output))
  }

}
