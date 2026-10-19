package com.peco2282.obfaryn.platform.paper

import com.peco2282.obfaryn.api.MapPlatformOptions
import com.peco2282.obfaryn.api.PlatformContext
import com.peco2282.obfaryn.api.context.ResourceEntry
import com.peco2282.obfaryn.core.ObfuscationConfig
import com.peco2282.obfaryn.core.Obfuscator
import com.peco2282.obfaryn.core.keep.KeepRules
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class PaperPlatformTest {
  @TempDir
  lateinit var dir: Path
  private val platform = PaperPlatformProvider().create()

  private fun context(vararg entries: ResourceEntry) = object : PlatformContext {
    override val classes = emptyMap<String, ClassNode>()
    override val resources = entries.associateBy { it.name }
  }

  @Test
  fun providerAndImplementationShareMetadata() {
    val provider = PaperPlatformProvider()
    assertEquals("paper", provider.metadata.id)
    assertTrue(provider.metadata.description.isNotBlank())
    assertSame(provider.metadata, provider.create().metadata)
  }

  @Test
  fun detectsDescriptorsAndOnlyUsesTopLevelEntrypoints() {
    assertEquals(100, platform.detect(context(ResourceEntry("paper-plugin.yml", byteArrayOf()))).confidence)
    assertEquals(80, platform.detect(context(ResourceEntry("plugin.yml", byteArrayOf()))).confidence)
    assertFalse(platform.detect(context()).detected)
    val context = context(
      ResourceEntry(
        "paper-plugin.yml", """
      main: 'sample.Main' # entrypoint
      bootstrapper: sample.Bootstrap
      loader: "sample.Loader"
      dependencies:
        main: not.an.Entrypoint
    """.trimIndent().toByteArray()
      )
    )
    assertEquals(
      setOf("sample/Main", "sample/Bootstrap", "sample/Loader"),
      platform.entrypoints(context, MapPlatformOptions())
    )
    val keep = KeepRules()
    platform.configure(context, MapPlatformOptions(), keep)
    assertTrue(keep.resource("paper-plugin.yml"))
    assertTrue(keep.className("sample/Main"))
    assertFalse(keep.method("sample/Main", "privateHelper", "()V"))
  }

  @Test
  fun excludesHotCallbacksAndTheirApplicationDependenciesFromVirtualization() {
    fun method(access: Int, name: String, descriptor: String, call: Triple<String, String, String>? = null) =
      MethodNode(access, name, descriptor, null, null).apply {
        call?.let { instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, it.first, it.second, it.third, false)) }
        instructions.add(
          InsnNode(
            when (Type.getReturnType(descriptor).sort) {
              Type.VOID -> Opcodes.RETURN
              Type.OBJECT, Type.ARRAY -> Opcodes.ARETURN
              else -> Opcodes.IRETURN
            }
          )
        )
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
    platform.configure(context, MapPlatformOptions(), keep)
    val excluded = keep.virtualizationExclusions()
    assertEquals("paper scheduled callback", excluded["sample/TickTask.run()V"])
    assertEquals("paper hot callback dependency", excluded["sample/TickTask.hot()V"])
    assertEquals("paper hot callback dependency", excluded["sample/TickTask.deep()V"])
    assertEquals("paper tick callback", excluded["sample/Ticker.tick()V"])
    assertEquals("paper event callback", excluded["sample/Listener.onMove(Ljava/lang/Object;)V"])
    assertEquals("paper plugin lifecycle", excluded["sample/Main.onEnable()V"])
    assertFalse("sample/TickTask.getTickKey()Ljava/lang/String;" in excluded)
  }

  @Test
  fun serviceProviderIsAppliedDuringRealObfuscation() {
    val input = dir.resolve("paper.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      for (name in listOf("sample/Main", "sample/Bootstrap", "sample/Helper")) {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitEnd()
        jar.putNextEntry(JarEntry("$name.class")); jar.write(writer.toByteArray()); jar.closeEntry()
      }
      jar.putNextEntry(JarEntry("paper-plugin.yml"))
      jar.write("name: Test\nmain: sample.Main\nbootstrapper: sample.Bootstrap\n".toByteArray())
      jar.closeEntry()
    }
    val output = dir.resolve("paper-obfuscated.jar")
    val result = Obfuscator(
      ObfuscationConfig(
        platform = "paper", seed = 19,
        stringEncryption = false, numberObfuscation = false, renameMethods = false, renameFields = false
      )
    )
      .obfuscate(input, output)
    assertEquals("paper", result.platform)
    JarFile(output.toFile()).use { jar ->
      assertNotNull(jar.getJarEntry("sample/Main.class"))
      assertNotNull(jar.getJarEntry("sample/Bootstrap.class"))
      assertNotNull(jar.getJarEntry("paper-plugin.yml"))
      assertNull(jar.getJarEntry("sample/Helper.class"))
    }
  }
}
