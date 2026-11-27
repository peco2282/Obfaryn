package com.peco2282.obfaryn.platform.mcmod

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

class McModPlatformTest {
  @TempDir
  lateinit var dir: Path

  private fun context(classes: Map<String, ClassNode> = emptyMap(), vararg resources: String) =
    object : PlatformContext {
      override val classes = classes
      override val resources = resources.associateWith { ResourceEntry(it, ByteArray(0)) }
    }

  private fun classNode(name: String, vararg interfaces: String) = ClassNode().apply {
    version = Opcodes.V17; access = Opcodes.ACC_PUBLIC; this.name = name; superName = "java/lang/Object"
    this.interfaces = interfaces.toMutableList()
  }

  private fun annotated(name: String, annotationDescriptor: String) = classNode(name).apply {
    visibleAnnotations = mutableListOf(AnnotationNode(annotationDescriptor))
  }

  private fun method(access: Int, name: String, descriptor: String, call: Triple<String, String, String>? = null) =
    MethodNode(access, name, descriptor, null, null).apply {
      call?.let { instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, it.first, it.second, it.third, false)) }
      instructions.add(InsnNode(if (Type.getReturnType(descriptor).sort == Type.VOID) Opcodes.RETURN else Opcodes.IRETURN))
    }

  @Test
  fun providersExposeDistinctIdsSharedWithTheirImplementations() {
    val providers = listOf(FabricPlatformProvider(), ForgePlatformProvider(), NeoForgePlatformProvider())
    assertEquals(listOf("fabric", "forge", "neoforge"), providers.map { it.metadata.id })
    providers.forEach {
      assertTrue(it.metadata.description.isNotBlank())
      assertSame(it.metadata, it.create().metadata)
    }
  }

  @Test
  fun fabricDetectsItsDescriptorAndInitializerImplementations() {
    val fabric = FabricPlatformProvider().create()
    assertEquals(100, fabric.detect(context(emptyMap(), "fabric.mod.json")).confidence)

    val init = classNode("sample/ExampleMod", "net/fabricmc/api/ModInitializer")
    val ctx = context(mapOf(init.name to init))
    assertEquals(80, fabric.detect(ctx).confidence)
    assertEquals(setOf("sample/ExampleMod"), fabric.entrypoints(ctx, MapPlatformOptions()))
    assertFalse(fabric.detect(context()).detected)
  }

  @Test
  fun forgeDetectsItsModAnnotationAndTomlButYieldsToNeoForgeDescriptor() {
    val forge = ForgePlatformProvider().create()
    val mod = annotated("sample/ForgeMod", "Lnet/minecraftforge/fml/common/Mod;")
    assertEquals(100, forge.detect(context(mapOf(mod.name to mod))).confidence)
    assertEquals(setOf("sample/ForgeMod"), forge.entrypoints(context(mapOf(mod.name to mod)), MapPlatformOptions()))

    assertEquals(70, forge.detect(context(emptyMap(), "META-INF/mods.toml")).confidence)
    // A NeoForge archive that also ships mods.toml must not be read as Forge.
    assertFalse(forge.detect(context(emptyMap(), "META-INF/mods.toml", "META-INF/neoforge.mods.toml")).detected)
  }

  @Test
  fun neoForgeDetectsItsOwnModAnnotationAndDescriptor() {
    val neo = NeoForgePlatformProvider().create()
    assertEquals("neoforge", neo.id)
    val mod = annotated("sample/NeoMod", "Lnet/neoforged/fml/common/Mod;")
    assertEquals(100, neo.detect(context(mapOf(mod.name to mod))).confidence)
    assertEquals(90, neo.detect(context(emptyMap(), "META-INF/neoforge.mods.toml")).confidence)
    // A plain Forge mods.toml is not a NeoForge archive.
    assertFalse(neo.detect(context(emptyMap(), "META-INF/mods.toml")).detected)
  }

  @Test
  fun configureKeepsEntrypointClassNamesAndResources() {
    val init = classNode("sample/ExampleMod", "net/fabricmc/api/ModInitializer")
    val ctx = context(mapOf(init.name to init), "fabric.mod.json", "assets/sample/lang/en_us.json")
    val keep = KeepRules()
    FabricPlatformProvider().create().configure(ctx, MapPlatformOptions(), keep)
    assertTrue(keep.className("sample/ExampleMod"))
    assertTrue(keep.resource("fabric.mod.json"))
    assertTrue(keep.resource("assets/sample/lang/en_us.json"))
    assertFalse(keep.method("sample/ExampleMod", "privateHelper", "()V"))
  }

  @Test
  fun forgeExcludesSubscribeEventHandlersAndTheirDependenciesFromVirtualization() {
    val mod = annotated("sample/ForgeMod", "Lnet/minecraftforge/fml/common/Mod;")
    val handlers = classNode("sample/Handlers").apply {
      methods.add(method(Opcodes.ACC_PUBLIC, "onTick", "(Ljava/lang/Object;)V", Triple(name, "deep", "()V")).apply {
        visibleAnnotations = mutableListOf(AnnotationNode("Lnet/minecraftforge/eventbus/api/SubscribeEvent;"))
      })
      methods.add(method(Opcodes.ACC_PRIVATE, "deep", "()V"))
      methods.add(method(Opcodes.ACC_PRIVATE, "unrelated", "()V"))
    }
    val ctx = context(listOf(mod, handlers).associateBy { it.name })
    val keep = KeepRules()
    ForgePlatformProvider().create().configure(ctx, MapPlatformOptions(), keep)
    val excluded = keep.virtualizationExclusions()
    assertEquals("minecraft mod event handler", excluded["sample/Handlers.onTick(Ljava/lang/Object;)V"])
    assertEquals("minecraft mod hot callback dependency", excluded["sample/Handlers.deep()V"])
    assertFalse("sample/Handlers.unrelated()V" in excluded)

    val disabled = KeepRules()
    ForgePlatformProvider().create()
      .configure(ctx, MapPlatformOptions(mapOf("virtualization-protect-hot-paths" to "false")), disabled)
    assertTrue(disabled.virtualizationExclusions().isEmpty())
  }

  @Test
  fun forgeExcludesSubscribeEventHandlersUnderTheEventbus7PackageToo() {
    // eventbus moved SubscribeEvent from net.minecraftforge.eventbus.api to
    // net.minecraftforge.eventbus.api.listener between 6.x and 7.x; mods built against either
    // must have their handlers excluded from virtualization the same way.
    val mod = annotated("sample/ForgeMod", "Lnet/minecraftforge/fml/common/Mod;")
    val handlers = classNode("sample/Handlers").apply {
      methods.add(method(Opcodes.ACC_PUBLIC, "onTick", "(Ljava/lang/Object;)V").apply {
        visibleAnnotations = mutableListOf(AnnotationNode("Lnet/minecraftforge/eventbus/api/listener/SubscribeEvent;"))
      })
    }
    val ctx = context(listOf(mod, handlers).associateBy { it.name })
    val keep = KeepRules()
    ForgePlatformProvider().create().configure(ctx, MapPlatformOptions(), keep)
    assertEquals("minecraft mod event handler", keep.virtualizationExclusions()["sample/Handlers.onTick(Ljava/lang/Object;)V"])
  }

  @Test
  fun fabricDeclaresNoAnnotationDrivenVirtualizationExclusions() {
    val init = classNode("sample/ExampleMod", "net/fabricmc/api/ModInitializer").apply {
      methods.add(method(Opcodes.ACC_PUBLIC, "onInitialize", "()V"))
    }
    val keep = KeepRules()
    FabricPlatformProvider().create().configure(context(mapOf(init.name to init)), MapPlatformOptions(), keep)
    assertTrue(keep.virtualizationExclusions().isEmpty())
  }

  @Test
  fun serviceProviderIsAppliedDuringRealObfuscation() {
    val input = dir.resolve("mod.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      val entrypoint = ClassWriter(0).apply {
        visit(
          Opcodes.V17,
          Opcodes.ACC_PUBLIC,
          "sample/ExampleMod",
          null,
          "java/lang/Object",
          arrayOf("net/fabricmc/api/ModInitializer")
        )
        visitEnd()
      }
      jar.putNextEntry(JarEntry("sample/ExampleMod.class")); jar.write(entrypoint.toByteArray()); jar.closeEntry()
      val helper = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Helper", null, "java/lang/Object", null); visitEnd()
      }
      jar.putNextEntry(JarEntry("sample/Helper.class")); jar.write(helper.toByteArray()); jar.closeEntry()
      jar.putNextEntry(JarEntry("fabric.mod.json")); jar.write("{\"id\":\"sample\"}".toByteArray()); jar.closeEntry()
    }

    val output = dir.resolve("mod-obfuscated.jar")
    val result = Obfuscator(
      ObfuscationConfig(
        platform = "fabric", seed = 7, applicationPackages = listOf("sample"),
        renameClasses = true, renameMethods = false, renameFields = false,
        stringEncryption = false, numberObfuscation = false, verifyOutput = true,
      )
    ).obfuscate(input, output)

    assertEquals("fabric", result.platform)
    JarFile(output.toFile()).use { jar ->
      assertNotNull(jar.getJarEntry("sample/ExampleMod.class"), "kept Fabric entrypoint class name")
      assertNull(jar.getJarEntry("sample/Helper.class"), "non-entrypoint class renamed")
      assertNotNull(jar.getJarEntry("fabric.mod.json"), "mod descriptor kept")
    }
  }
}
