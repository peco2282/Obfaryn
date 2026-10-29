package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.assembly.TransformationPackage
import com.peco2282.obfaryn.api.transformer.*
import com.peco2282.obfaryn.core.fixtures.JavaJarFixture
import com.peco2282.obfaryn.standard.StandardPackage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.commons.*
import org.objectweb.asm.tree.ClassNode
import java.net.URLClassLoader
import java.nio.file.Path

class StandardAssemblyTest {
  @TempDir lateinit var dir: Path

  @Test fun `standard recipe runs under the standard-free engine including explicit virtualization`() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Main" to """
      package app;
      import com.peco2282.obfaryn.annotations.Virtualize;
      public class Main {
        @Virtualize public static int answer(int value) { return value * 3 + 7; }
        public static String message() { return "hello"; }
      }
    """.trimIndent()))
    val options = EngineOptions(applicationPackages = listOf("app"), seed = 19,
      configuration = { keepClass("app.Main") })
    val result = TransformationEngine(options).transform(input, dir.resolve("standard.jar"), listOf(StandardPackage()))
    assertTrue(result.transformerReports.any { it.id == "virtualization" && it.metrics["transformed"] == 1L })
    URLClassLoader(arrayOf(result.output.toUri().toURL()), null).use {
      val type = it.loadClass("app.Main")
      assertEquals(19, type.getMethod("answer", Int::class.javaPrimitiveType).invoke(null, 4))
      assertEquals("hello", type.getMethod("message").invoke(null))
    }
  }

  @Test fun `replacing the standard renamer publishes custom mappings and preserves final-name linkage`() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Main" to """
      package app;
      public class Main {
        public static int answer(int value) { return helper(value) + 7; }
        private static int helper(int value) { return value * 3; }
      }
    """.trimIndent()))
    val custom = object : RenamingTransformer {
      override val id = "rename"
      override val order = 40
      override val classMapping = mapOf("app/Main" to "custom/Entry")
      override val methodMapping = mapOf(MemberKey("app/Main", "answer", "(I)I") to "a",
        MemberKey("app/Main", "helper", "(I)I") to "h")
      override val fieldMapping = emptyMap<MemberKey, String>()
      override fun transform(context: com.peco2282.obfaryn.api.ObfuscationContext) {
        val remapper = object : Remapper() {
          override fun map(name: String) = classMapping[name] ?: name
          override fun mapMethodName(owner: String, name: String, descriptor: String) = methodMapping[MemberKey(owner, name, descriptor)] ?: name
        }
        for (node in context.classes.values.toList()) {
          val mapped = ClassNode(); node.accept(ClassRemapper(mapped, remapper)); mapped.methods.reverse()
          context.removeClass(node.name); context.putClass(mapped)
        }
      }
    }
    val result = Obfuscator(ObfuscationConfig(applicationPackages = listOf("app"), callIndirection = true,
      verifyOutput = true, seed = 22), pipelineConfigurers = listOf(PipelineConfigurer { replace("rename", custom) }))
      .obfuscate(input, dir.resolve("custom.jar"))
    assertEquals(1, result.classesRenamed)
    assertTrue(String(java.nio.file.Files.readAllBytes(result.mapping!!)).contains("app/Main -> custom/Entry"))
    assertTrue(result.transformerReports.any { it.id == "call-indirection" && (it.metrics["indirectedCalls"] ?: 0) > 0 })
    URLClassLoader(arrayOf(result.output.toUri().toURL()), null).use {
      assertEquals(19, it.loadClass("custom.Entry").getMethod("a", Int::class.javaPrimitiveType).invoke(null, 4))
    }
  }
  @Test fun `a later user recipe can preserve names in an earlier standard recipe`() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Main" to
      "package app; public class Main { public static int answer() { return 7; } }"))
    val rules = TransformationPackage { context, _ -> context.configuration.keepClass("app.Main") }
    val result = TransformationEngine(EngineOptions(applicationPackages = listOf("app")))
      .transform(input, dir.resolve("late-keep.jar"), listOf(StandardPackage(), rules))
    assertEquals("", result.mapping)
    URLClassLoader(arrayOf(result.output.toUri().toURL()), null).use {
      assertEquals(7, it.loadClass("app.Main").getMethod("answer").invoke(null))
    }
  }

  @Test fun `an empty package list does not require standard VM eligibility`() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Main" to """
      package app;
      import com.peco2282.obfaryn.annotations.Virtualize;
      public class Main { @Virtualize public static synchronized int answer() { return 7; } }
    """.trimIndent()))
    val result = Obfuscator(packages = emptyList()).obfuscate(input, dir.resolve("empty.jar"))
    assertTrue(result.transformerReports.isEmpty())
    URLClassLoader(arrayOf(result.output.toUri().toURL()), null).use {
      assertEquals(7, it.loadClass("app.Main").getMethod("answer").invoke(null))
    }
  }

}
