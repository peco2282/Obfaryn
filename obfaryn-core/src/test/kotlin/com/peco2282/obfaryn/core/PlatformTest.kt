package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.*
import com.peco2282.obfaryn.api.PlatformProvider.Companion.metadata
import com.peco2282.obfaryn.api.context.ResourceEntry
import com.peco2282.obfaryn.core.keep.KeepRules
import com.peco2282.obfaryn.core.platform.GenericPlatform
import com.peco2282.obfaryn.core.platform.PaperPlatform
import com.peco2282.obfaryn.core.platform.Platforms
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
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
    assertEquals("paper", Platforms.select("auto", context(ResourceEntry("plugin.yml", byteArrayOf()))).id)
    assertThrows(IllegalStateException::class.java) { Platforms.select("missing", context()) }
  }

  @Test fun paperOnlyUsesTopLevelEntrypoints() {
    val context = context(ResourceEntry("paper-plugin.yml", """
      main: 'sample.Main' # entrypoint
      bootstrapper: sample.Bootstrap
      loader: "sample.Loader"
      dependencies:
        main: not.an.Entrypoint
    """.trimIndent().toByteArray()))
    val platform = PaperPlatform()
    assertEquals(setOf("sample/Main", "sample/Bootstrap", "sample/Loader"), platform.entrypoints(context, MapPlatformOptions()))
    val keep = KeepRules()
    platform.configure(context, MapPlatformOptions(), keep)
    assertTrue(keep.resource("paper-plugin.yml"))
    assertTrue(keep.className("sample/Main"))
    assertFalse(keep.method("sample/Main", "privateHelper", "()V"))
  }

  @Test fun paperExcludesHotCallbacksAndTheirApplicationDependenciesFromVirtualization() {
    fun method(access: Int, name: String, descriptor: String, call: Triple<String, String, String>? = null) =
      MethodNode(access, name, descriptor, null, null).apply {
        call?.let { instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, it.first, it.second, it.third, false)) }
        instructions.add(InsnNode(when (org.objectweb.asm.Type.getReturnType(descriptor).sort) {
          org.objectweb.asm.Type.VOID -> Opcodes.RETURN
          org.objectweb.asm.Type.OBJECT, org.objectweb.asm.Type.ARRAY -> Opcodes.ARETURN
          else -> Opcodes.IRETURN
        }))
      }
    val task = ClassNode().apply {
      version = Opcodes.V17; access = Opcodes.ACC_PUBLIC; name = "sample/TickTask"
      superName = "org/bukkit/scheduler/BukkitRunnable"
      methods.add(method(Opcodes.ACC_PUBLIC, "run", "()V", Triple(name, "hot", "()V")))
      methods.add(method(Opcodes.ACC_STATIC, "hot", "()V", Triple(name, "deep", "()V")))
      methods.add(method(Opcodes.ACC_STATIC, "deep", "()V"))
      methods.add(method(Opcodes.ACC_PUBLIC, "getTickKey", "()Ljava/lang/String;"))
    }
    val ticker = ClassNode().apply {
      version = Opcodes.V17; access = Opcodes.ACC_PUBLIC; name = "sample/Ticker"; superName = "java/lang/Object"
      methods.add(method(Opcodes.ACC_PRIVATE, "tick", "()V"))
    }
    val listener = ClassNode().apply {
      version = Opcodes.V17; access = Opcodes.ACC_PUBLIC; name = "sample/Listener"; superName = "java/lang/Object"
      methods.add(method(Opcodes.ACC_PUBLIC, "onMove", "(Ljava/lang/Object;)V").apply {
        visibleAnnotations = mutableListOf(AnnotationNode("Lorg/bukkit/event/EventHandler;"))
      })
    }
    val plugin = ClassNode().apply {
      version = Opcodes.V17; access = Opcodes.ACC_PUBLIC; name = "sample/Main"; superName = "java/lang/Object"
      methods.add(method(Opcodes.ACC_PUBLIC, "onEnable", "()V"))
    }
    val context = object : PlatformContext {
      override val classes = listOf(task, ticker, listener, plugin).associateBy { it.name }
      override val resources = mapOf("plugin.yml" to ResourceEntry("plugin.yml", "main: sample.Main".toByteArray()))
    }
    val keep = KeepRules()
    PaperPlatform().configure(context, MapPlatformOptions(), keep)
    val excluded = keep.virtualizationExclusions()
    assertEquals("paper scheduled callback", excluded["sample/TickTask.run()V"])
    assertEquals("paper hot callback dependency", excluded["sample/TickTask.hot()V"])
    assertEquals("paper hot callback dependency", excluded["sample/TickTask.deep()V"])
    assertEquals("paper tick callback", excluded["sample/Ticker.tick()V"])
    assertEquals("paper event callback", excluded["sample/Listener.onMove(Ljava/lang/Object;)V"])
    assertEquals("paper plugin lifecycle", excluded["sample/Main.onEnable()V"])
    assertFalse("sample/TickTask.getTickKey()Ljava/lang/String;" in excluded)
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
}
