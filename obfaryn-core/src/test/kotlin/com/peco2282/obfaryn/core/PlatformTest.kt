package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.*
import com.peco2282.obfaryn.api.PlatformProvider.Companion.metadata
import com.peco2282.obfaryn.api.context.ResourceEntry
import com.peco2282.obfaryn.core.keep.KeepRules
import com.peco2282.obfaryn.core.platform.GenericPlatform
import com.peco2282.obfaryn.core.platform.Platforms
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.tree.ClassNode
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class PlatformTest {
  @TempDir lateinit var dir: Path
  private fun context(vararg entries: ResourceEntry) = object : PlatformContext {
    override val classes = emptyMap<String, ClassNode>()
    override val resources = entries.associateBy { it.name }
  }

  @Test fun configurationDslExecutes() {
    val options = MapPlatformOptions(mapOf("enabled" to "true"))
    assertTrue(options.boolean("enabled", false))
    assertEquals("true", options.require("enabled"))
  }

  @Test fun genericIsFallback() {
    assertEquals("generic", Platforms.select("auto", context()).id)
    assertEquals("generic", Platforms.select("auto", context(ResourceEntry("plugin.yml", byteArrayOf()))).id)
    assertThrows(IllegalStateException::class.java) { Platforms.select("missing", context()) }
  }

  @Test fun serviceLoaderPlatformIsSelectedAndConfigurationIsApplied() {
    val context = context(ResourceEntry("test-platform.txt", byteArrayOf()))
    val platform = Platforms.select("auto", context)
    assertEquals("test-platform", platform.id)
    assertEquals("test-platform", Platforms.select("test-platform", context()).id)
    val keep = KeepRules().keepClass("sample.UserRule")
    platform.configure(context, MapPlatformOptions(), keep)
    assertTrue(keep.className("sample/DefaultRule"))
    assertTrue(keep.className("sample/UserRule"))
    assertTrue(keep.resource("test-platform.txt"))
    assertEquals(setOf("sample/Main"), platform.entrypoints(context, MapPlatformOptions()))
  }

  @Test fun duplicateIdsAreRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      Platforms.select("auto", context(), listOf(GenericPlatform(), GenericPlatform()))
    }
  }

  @Test fun keepRulesMergeAllKindsAndDoNotShareState() {
    val source = KeepRules().keepClass("sample.Api").keepPackage("sample.lib")
      .keepClassNameOnly("sample.Main").keepClassNameAndPublicMembers("sample.Public")
      .keepMethod("sample.Other", "run", "()V").keepField("sample.Other", "value")
      .keepResource("config.yml")
    source.add("sample.deep.**")
    source.keepReflectiveMethod("invoke")
    source.keepReflectiveField("reflective")
    val target = KeepRules()
    source.applyTo(target)
    source.applyTo(source)
    assertTrue(target.method("sample/Api", "any", "()V"))
    assertTrue(target.className("sample/lib/Helper"))
    assertTrue(target.className("sample/deep/nested/Helper"))
    assertTrue(target.className("sample/Main"))
    assertFalse(target.method("sample/Main", "helper", "()V"))
    assertTrue(target.publicMembers("sample/Public"))
    assertTrue(target.method("sample/Other", "run", "()V"))
    assertTrue(target.field("sample/Other", "value"))
    assertTrue(target.method("unknown/Type", "invoke", "()V"))
    assertTrue(target.field("unknown/Type", "reflective"))
    assertTrue(target.resource("config.yml"))
    source.keepClass("sample.Later")
    assertFalse(target.className("sample/Later"))
  }

  @Test fun obfuscatorAppliesPlatformDefaultsAndUserConfiguration() {
    val input = dir.resolve("input.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      for (name in listOf("sample/DefaultRule", "sample/UserRule", "sample/Renamed")) {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitEnd()
        jar.putNextEntry(JarEntry("$name.class"))
        jar.write(writer.toByteArray())
        jar.closeEntry()
      }
      jar.putNextEntry(JarEntry("test-platform.txt"))
      jar.closeEntry()
    }
    val result = Obfuscator(ObfuscationConfig(dryRun = true, stringEncryption = false,
      numberObfuscation = false, platformConfiguration = { keepClass("sample.UserRule") }))
      .obfuscate(input, dir.resolve("output.jar"))
    assertEquals("test-platform", result.platform)
    assertEquals(1, result.classesRenamed)
    assertTrue(result.mappingPreview!!.contains("sample/Renamed ->"))
    assertFalse(result.mappingPreview.contains("sample/DefaultRule ->"))
    assertFalse(result.mappingPreview.contains("sample/UserRule ->"))
  }

  @Test fun platformExtensionsAddArtifactsAndMapCustomAnnotationPolicy() {
    val input = dir.resolve("extensions-input.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      val writer = ClassWriter(0)
      writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Protected", null, "java/lang/Object", null)
      writer.visitAnnotation("Lcustom/NoObfuscation;", false).visitEnd()
      writer.visitEnd()
      jar.putNextEntry(JarEntry("sample/Protected.class")); jar.write(writer.toByteArray()); jar.closeEntry()
      jar.putNextEntry(JarEntry("test-platform.txt")); jar.closeEntry()
    }
    val output = dir.resolve("extensions-output.jar")
    Obfuscator(ObfuscationConfig(platform = "test-platform", platformOptions = mapOf("extensions" to "true"),
      stringEncryption = false, numberObfuscation = false, renameMethods = false, renameFields = false))
      .obfuscate(input, output)
    JarFile(output.toFile()).use { jar ->
      assertNotNull(jar.getJarEntry("sample/Protected.class"))
      assertNotNull(jar.getJarEntry("platform/generated.txt"))
      assertNotNull(jar.getJarEntry("archive/generated.txt"))
      assertTrue(jar.entries().asSequence().any { it.name.endsWith(".class") && it.name != "sample/Protected.class" })
    }
  }
}

class TestPlatformProvider : PlatformProvider {
  override val metadata: PlatformMetadata = metadata {
    id = "test-platform"
    displayName = "Test Platform"
    implementationVersion = "1.0"
  }
  override fun create(): Platform = TestPlatform(metadata)
}

class TestPlatform(override val metadata: PlatformMetadata) : Platform {
  override fun detect(context: PlatformContext): PlatformDetection = if ("test-platform.txt" in context.resources)
    DefaultPlatformDetection(90, listOf("test-platform.txt found")) else DefaultPlatformDetection(0)

  override fun configure(context: PlatformContext, options: PlatformOptions, target: PlatformConfiguration) {
    target.keepClass("sample.DefaultRule")
    target.keepResource("test-platform.txt")
  }
  override fun entrypoints(context: PlatformContext, options: PlatformOptions) = setOf("sample/Main")

  override fun extensions(): Collection<PlatformExtension> = listOf(
    AnnotationPolicyProvider {
      listOf(object : AnnotationPolicy {
        override val descriptor = "Lcustom/NoObfuscation;"
        override val action = AnnotationPolicyAction.NO_OBFUSCATION
        override val targets = setOf(AnnotationPolicyTarget.CLASS)
        override val reason = "custom test policy"
      })
    },
    ResourceContributor { _, options, sink -> if (options.boolean("extensions", false)) sink.add("platform/generated.txt", "resource".toByteArray()) },
    ClassContributor { _, options, sink -> if (options.boolean("extensions", false)) sink.add(testClassBytes("platform/Generated")) },
    ArchiveContributor { _, options, sink ->
      if (!options.boolean("extensions", false)) return@ArchiveContributor
      val bytes = ByteArrayOutputStream().also { output ->
        JarOutputStream(output).use { jar ->
          jar.putNextEntry(JarEntry("archive/Generated.class")); jar.write(testClassBytes("archive/Generated")); jar.closeEntry()
          jar.putNextEntry(JarEntry("archive/generated.txt")); jar.write("archive".toByteArray()); jar.closeEntry()
        }
      }.toByteArray()
      sink.add(bytes, ArchiveContributionMode.MERGE)
    },
  )
}

private fun testClassBytes(name: String): ByteArray = ClassWriter(0).also { writer ->
  writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
  writer.visitEnd()
}.toByteArray()
