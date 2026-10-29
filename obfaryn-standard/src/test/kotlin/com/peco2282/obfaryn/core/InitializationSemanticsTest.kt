package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.fixtures.JavaJarFixture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Files
import java.util.jar.JarFile
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnNode

class InitializationSemanticsTest {
  @TempDir lateinit var dir: Path

  @Test fun superclassCanReadPreparedConstantsBeforeSubclassInitializer() {
    val input = JavaJarFixture.compile(dir, mapOf(
      "app/Base" to "package app; public class Base { public static int observed = Child.read(); }",
      "app/Child" to """
        package app; public class Child extends Base {
          public static final int LIMIT = 7;
          public static int read() { return LIMIT; }
          public static int result() { return observed; }
        }
      """.trimIndent(),
    ))
    // javac inlines constant variables. Model an independently emitted JVM GETSTATIC consumer.
    val patched = dir.resolve("getstatic.jar")
    JarFile(input.toFile()).use { jar ->
      JarOutputStream(Files.newOutputStream(patched)).use { out ->
        for (entry in jar.entries()) {
          var bytes = jar.getInputStream(entry).use { it.readBytes() }
          if (entry.name == "app/Child.class") {
            val node = ClassNode().also { ClassReader(bytes).accept(it, 0) }
            node.methods.single { it.name == "read" }.apply {
              instructions.clear()
              instructions.add(FieldInsnNode(Opcodes.GETSTATIC, "app/Child", "LIMIT", "I"))
              instructions.add(InsnNode(Opcodes.IRETURN))
            }
            bytes = ClassWriter(0).also { node.accept(it) }.toByteArray()
          }
          out.putNextEntry(JarEntry(entry.name)); out.write(bytes); out.closeEntry()
        }
      }
    }
    checkResult(patched, "result", 7, strings = false, numbers = true)
  }

  @Test fun superclassCanCallSubclassStringMethodBeforeSubclassInitializer() {
    for ((index, text) in listOf("ready", "x".repeat(30000)).withIndex()) {
      val fixtureDir = dir.resolve("string-$index")
      val input = JavaJarFixture.compile(fixtureDir, mapOf(
        "app/Base" to "package app; public class Base { public static String observed = Child.text(); }",
        "app/Child" to """
          package app; public class Child extends Base {
            public static String text() { return "$text"; }
            public static String result() { return observed; }
          }
        """.trimIndent(),
      ))
      checkResult(input, "result", text, strings = true, numbers = false)
    }
  }

  @Test fun defaultInterfaceInitializationAlsoKeepsPreparedConstants() {
    val input = JavaJarFixture.compile(dir, mapOf(
      "app/Parent" to """
        package app; public interface Parent {
          String OBSERVED = Child.text();
          default int marker() { return 1; }
        }
      """.trimIndent(),
      "app/Child" to """
        package app; public class Child implements Parent {
          public static final String READY = "ready";
          public static String text() {
            try { return (String) Child.class.getField(String.join("", "REA", "DY")).get(null); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
          }
          public static String result() { return OBSERVED; }
        }
      """.trimIndent(),
    ))
    checkResult(input, "result", "ready", strings = true, numbers = false)
  }

  private fun checkResult(input: Path, method: String, expected: Any, strings: Boolean, numbers: Boolean) {
    fun run(jar: Path): Any? = URLClassLoader(arrayOf(jar.toUri().toURL()), null).use {
      it.loadClass("app.Child").getMethod(method).invoke(null)
    }
    assertEquals(expected, run(input), "untransformed oracle")
    val output = dir.resolve("output.jar")
    Obfuscator(ObfuscationConfig(applicationPackages = listOf("app"), seed = 42,
      stringEncryption = strings, numberObfuscation = numbers,
      renameClasses = false, renameMethods = false, renameFields = false,
      verifyOutput = true)).obfuscate(input, output)
    assertEquals(expected, run(output), "transformed behavior")
  }
}
