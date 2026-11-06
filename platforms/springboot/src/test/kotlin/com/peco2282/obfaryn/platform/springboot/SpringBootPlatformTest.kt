package com.peco2282.obfaryn.platform.springboot

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
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.JarFile
import java.util.jar.Manifest

private fun manifestResource(startClass: String? = null): ResourceEntry {
  val manifest = Manifest()
  manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
  if (startClass != null) manifest.mainAttributes.putValue("Start-Class", startClass)
  val out = ByteArrayOutputStream()
  manifest.write(out)
  return ResourceEntry("META-INF/MANIFEST.MF", out.toByteArray())
}

class SpringBootPlatformTest {
  @TempDir
  lateinit var dir: Path
  private val platform = SpringBootPlatformProvider().create()

  private fun context(classes: Map<String, ClassNode> = emptyMap(), vararg entries: ResourceEntry) =
    object : PlatformContext {
      override val classes = classes
      override val resources = entries.associateBy { it.name }
    }

  private fun applicationClass(name: String) = ClassNode().apply {
    version = Opcodes.V17; access = Opcodes.ACC_PUBLIC; this.name = name; superName = "java/lang/Object"
    visibleAnnotations = mutableListOf(AnnotationNode("Lorg/springframework/boot/autoconfigure/SpringBootApplication;"))
  }

  @Test
  fun providerAndImplementationShareMetadata() {
    val provider = SpringBootPlatformProvider()
    assertEquals("springboot", provider.metadata.id)
    assertTrue(provider.metadata.description.isNotBlank())
    assertSame(provider.metadata, provider.create().metadata)
  }

  @Test
  fun detectsAnAnnotatedApplicationClassWithHighConfidence() {
    val app = applicationClass("sample/Application")
    assertEquals(100, platform.detect(context(mapOf(app.name to app))).confidence)
  }

  @Test
  fun detectsAStartClassManifestWithLowerConfidence() {
    assertEquals(60, platform.detect(context(emptyMap(), manifestResource("sample.Application"))).confidence)
  }

  @Test
  fun doesNotDetectAnUnrelatedArchive() {
    assertFalse(platform.detect(context()).detected)
  }

  @Test
  fun entrypointsCombineTheAnnotatedClassAndTheManifestStartClass() {
    val app = applicationClass("sample/Application")
    val ctx = context(mapOf(app.name to app), manifestResource("sample.other.Launcher"))
    assertEquals(setOf("sample/Application", "sample/other/Launcher"), platform.entrypoints(ctx, MapPlatformOptions()))
  }

  @Test
  fun configureKeepsEntrypointClassNamesOnly() {
    val app = applicationClass("sample/Application")
    val ctx = context(mapOf(app.name to app))
    val keep = KeepRules()
    platform.configure(ctx, MapPlatformOptions(), keep)
    assertTrue(keep.className("sample/Application"))
    assertFalse(keep.method("sample/Application", "privateHelper", "()V"))
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

    val app = applicationClass("sample/Application").apply {
      methods.add(method(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", Triple(name, "run", "()V")))
      methods.add(method(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "run", "()V"))
    }
    val scheduler = ClassNode().apply {
      version = Opcodes.V17; access = Opcodes.ACC_PUBLIC; name = "sample/Scheduler"; superName = "java/lang/Object"
      methods.add(method(Opcodes.ACC_PUBLIC, "run", "()V", Triple(name, "deep", "()V")).apply {
        visibleAnnotations = mutableListOf(AnnotationNode("Lorg/springframework/scheduling/annotation/Scheduled;"))
      })
      methods.add(method(Opcodes.ACC_PRIVATE, "deep", "()V"))
    }
    val listener = ClassNode().apply {
      version = Opcodes.V17; access = Opcodes.ACC_PUBLIC; name = "sample/Listener"; superName = "java/lang/Object"
      methods.add(method(Opcodes.ACC_PUBLIC, "onEvent", "(Ljava/lang/Object;)V").apply {
        visibleAnnotations = mutableListOf(AnnotationNode("Lorg/springframework/context/event/EventListener;"))
      })
    }
    val async = ClassNode().apply {
      version = Opcodes.V17; access = Opcodes.ACC_PUBLIC; name = "sample/Async"; superName = "java/lang/Object"
      methods.add(method(Opcodes.ACC_PUBLIC, "submit", "()V").apply {
        visibleAnnotations = mutableListOf(AnnotationNode("Lorg/springframework/scheduling/annotation/Async;"))
      })
      methods.add(method(Opcodes.ACC_PRIVATE, "unrelated", "()V"))
    }
    val ctx = context(listOf(app, scheduler, listener, async).associateBy { it.name })
    val keep = KeepRules()
    platform.configure(ctx, MapPlatformOptions(), keep)
    val excluded = keep.virtualizationExclusions()
    assertEquals("spring boot application entrypoint", excluded["sample/Application.main([Ljava/lang/String;)V"])
    assertEquals("spring hot callback dependency", excluded["sample/Application.run()V"])
    assertEquals("spring scheduled task", excluded["sample/Scheduler.run()V"])
    assertEquals("spring hot callback dependency", excluded["sample/Scheduler.deep()V"])
    assertEquals("spring event listener", excluded["sample/Listener.onEvent(Ljava/lang/Object;)V"])
    assertEquals("spring async method", excluded["sample/Async.submit()V"])
    assertFalse("sample/Async.unrelated()V" in excluded)
    val coverageFirst = KeepRules()
    platform.configure(ctx, MapPlatformOptions(mapOf("virtualization-protect-hot-paths" to "false")), coverageFirst)
    assertTrue(coverageFirst.virtualizationExclusions().isEmpty())
    assertThrows(IllegalStateException::class.java) {
      platform.configure(ctx, MapPlatformOptions(mapOf("virtualization-protect-hot-paths" to "invalid")), KeepRules())
    }
  }

  @Test
  fun serviceProviderIsAppliedDuringRealObfuscation() {
    val input = dir.resolve("app.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      for (name in listOf("sample/Application", "sample/Helper")) {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitEnd()
        jar.putNextEntry(JarEntry("$name.class")); jar.write(writer.toByteArray()); jar.closeEntry()
      }
      val manifest = Manifest()
      manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
      manifest.mainAttributes.putValue("Start-Class", "sample.Application")
      jar.putNextEntry(JarEntry("META-INF/MANIFEST.MF")); manifest.write(jar); jar.closeEntry()
    }
    val output = dir.resolve("app-obfuscated.jar")
    val result = Obfuscator(
      ObfuscationConfig(
        platform = "springboot", seed = 19,
        stringEncryption = false, numberObfuscation = false, renameMethods = false, renameFields = false,
      )
    ).obfuscate(input, output)
    assertEquals("springboot", result.platform)
    JarFile(output.toFile()).use { jar ->
      assertNotNull(jar.getJarEntry("sample/Application.class"))
    }
  }
}
