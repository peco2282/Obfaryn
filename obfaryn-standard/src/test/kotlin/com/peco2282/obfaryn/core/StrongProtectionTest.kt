package com.peco2282.obfaryn.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.writeText

class StrongProtectionTest {
  @TempDir lateinit var dir: Path

  private fun input(): Path {
    val source = dir.resolve("Main.java")
    source.writeText( """
      package app;
      public class Main {
        public static String choose(int v) { return v > 2 ? "high" : "low"; }
        public static int hot(int v) { return v + 3; }
        public static synchronized int locked(int v) { return v + 2; }
        public static int guarded(int v) { try { return 10 / v; } catch (ArithmeticException e) { return -1; } }
      }
    """.trimIndent())
    val dependency = dir.resolve("Utility.java")
    dependency.writeText( "package dependency; public class Utility { public static String value() { return \"dependency-visible\"; } }")
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dir.toString(), source.toString(), dependency.toString()))
    val input = dir.resolve("input.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      for (name in listOf("app/Main", "dependency/Utility")) {
        val node = ClassNode().apply { ClassReader(Files.readAllBytes(dir.resolve("$name.class"))).accept(this, 0) }
        if (name == "app/Main") {
          node.invisibleAnnotations = mutableListOf(AnnotationNode("Lkotlin/jvm/internal/SourceDebugExtension;").apply {
            values = mutableListOf("value", listOf("SMAP\nOriginalSecret.kt\n"))
          })
          node.methods.single { it.name == "hot" }.invisibleAnnotations =
            mutableListOf(AnnotationNode("Lcom/peco2282/obfaryn/annotations/HotPath;"))
        }
        jar.putNextEntry(JarEntry("$name.class"))
        jar.write(ClassWriter(0).also { node.accept(it) }.toByteArray())
        jar.closeEntry()
      }
    }
    return input
  }

  @Test fun strongWithoutSelectionPreservesBehaviorWithoutVirtualization() {
    val output = dir.resolve("strong.jar")
    val result = Obfuscator(ObfuscationConfig(
      protectionLevel = ProtectionLevel.STRONG, applicationPackages = listOf("app"),
      keep = listOf("app.Main"), seed = 10,
    )).obfuscate(input(), output)
    assertEquals(0, result.methodsVirtualized)
    assertTrue(result.branchesObfuscated > 0)
    assertTrue(Files.exists(dir.resolve("strong.virtualization.json")))
    val report = result.virtualizationAnalysis!!
    assertEquals(0, report.eligible)
    assertEquals(0, report.rejected)
    assertTrue(report.methods.none { it.owner?.startsWith("dependency/") == true })
    assertTrue(report.methods.single { it.name == "hot" }.exclusionReason!!.contains("HotPath"))
    JarFile(output.toFile()).use { jar ->
      val bytes = jar.getInputStream(jar.getJarEntry("app/Main.class")).readBytes()
      assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("OriginalSecret.kt"))
      val node = ClassNode().apply { ClassReader(jar.getInputStream(jar.getJarEntry("dependency/Utility.class"))).accept(this, 0) }
      assertTrue(node.methods.flatMap { it.instructions.toArray().toList() }.filterIsInstance<LdcInsnNode>()
        .any { it.cst == "dependency-visible" })
    }
    URLClassLoader(arrayOf(output.toUri().toURL()), platformClassLoader()).use { loader ->
      val type = loader.loadClass("app.Main")
      val integer = Int::class.javaPrimitiveType!!
      assertEquals("high", type.getMethod("choose", integer).invoke(null, 3))
      assertEquals("low", type.getMethod("choose", integer).invoke(null, -3))
      assertEquals(7, type.getMethod("hot", integer).invoke(null, 4))
      assertEquals(-1, type.getMethod("guarded", integer).invoke(null, 0))
    }
  }

  @Test fun strongVirtualizesOnlyAnnotatedMethods() {
    val input = input()
    val annotated = dir.resolve("annotated.jar")
    JarFile(input.toFile()).use { source ->
      JarOutputStream(Files.newOutputStream(annotated)).use { target ->
        for (entry in source.entries()) {
          var bytes = source.getInputStream(entry).readBytes()
          if (entry.name == "app/Main.class") {
            val node = ClassNode().apply { ClassReader(bytes).accept(this, 0) }
            node.methods.single { it.name == "choose" }.invisibleAnnotations =
              mutableListOf(AnnotationNode("Lcom/peco2282/obfaryn/annotations/Virtualize;"))
            bytes = ClassWriter(0).also { node.accept(it) }.toByteArray()
          }
          target.putNextEntry(JarEntry(entry.name))
          target.write(bytes)
          target.closeEntry()
        }
      }
    }
    val output = dir.resolve("annotated-output.jar")
    val result = Obfuscator(ObfuscationConfig(
      protectionLevel = ProtectionLevel.STRONG, applicationPackages = listOf("app"),
      keep = listOf("app.Main"), seed = 10,
    )).obfuscate(annotated, output)
    assertEquals(1, result.methodsVirtualized)
    URLClassLoader(arrayOf(output.toUri().toURL()), platformClassLoader()).use { loader ->
      val method = loader.loadClass("app.Main").getMethod("choose", Int::class.javaPrimitiveType!!)
      assertEquals("high", method.invoke(null, 3))
      assertEquals("low", method.invoke(null, -3))
    }
  }

  @Test fun explicitCriticalSelectionStillFailsRatherThanFallingBack() {
    val output = dir.resolve("critical.jar")
    assertThrows(IllegalArgumentException::class.java) {
      Obfuscator(ObfuscationConfig(
        protectionLevel = ProtectionLevel.STRONG, applicationPackages = listOf("app"),
        virtualizeMethods = listOf("app/Main\\.locked.*"),
      )).obfuscate(input(), output)
    }
    assertFalse(Files.exists(output))
    assertTrue(Files.exists(dir.resolve("critical.virtualization.json")))
  }

  @Test fun strongProtectionRequiresApplicationScope() {
    assertThrows(IllegalArgumentException::class.java) {
      Obfuscator(ObfuscationConfig(protectionLevel = ProtectionLevel.STRONG)).obfuscate(input(), dir.resolve("none.jar"))
    }
  }
}
