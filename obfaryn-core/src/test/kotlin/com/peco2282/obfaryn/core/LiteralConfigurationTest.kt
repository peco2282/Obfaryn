package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.transformer.EncryptedString
import com.peco2282.obfaryn.transformer.StringEncryptionStrategy
import com.peco2282.obfaryn.transformer.XorStringEncryptionStrategy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

class LiteralConfigurationTest {
  @TempDir lateinit var dir: Path

  private fun input(): Path {
    val source = dir.resolve("App.java")
    source.toFile().writeText("""
      package sample;
      public class App {
        public static final String TEXT = "field secret";
        public static final int NUMBER = 12345;
        public static String message() { return "method secret"; }
        public static int number() { return 67890; }
      }
    """.trimIndent())
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
      "--release", "8", "-d", dir.toString(), source.toString()))
    return dir.resolve("in.jar").also { input ->
      JarOutputStream(Files.newOutputStream(input)).use { jar ->
        jar.putNextEntry(JarEntry("sample/App.class"))
        jar.write(Files.readAllBytes(dir.resolve("sample/App.class")))
        jar.closeEntry()
      }
    }
  }

  private fun config() = ObfuscationConfig(
    platform = "generic", applicationPackages = listOf("sample"), seed = 10,
    renameClasses = false, renameMethods = false, renameFields = false, verifyOutput = true,
  )

  @Test fun constantFieldSettingsAreIndependentAndDoNotDisableLiteralPasses() {
    val input = input()
    for (strings in listOf(false, true)) for (numbers in listOf(false, true)) {
      val output = dir.resolve("out-$strings-$numbers.jar")
      val result = Obfuscator(config().copy(
        stringConstantFields = strings, numberConstantFields = numbers,
      )).obfuscate(input, output)
      assertTrue(result.stringsEncrypted > 0)
      assertTrue(result.constantsObfuscated > 0)
      assertEquals(strings || numbers, result.transformerReports.any { it.id == "constant-fields" })
      JarFile(output.toFile()).use { jar ->
        val node = ClassNode()
        ClassReader(jar.getInputStream(jar.getJarEntry("sample/App.class")).readBytes()).accept(node, 0)
        assertEquals(if (strings) null else "field secret", node.fields.single { it.name == "TEXT" }.value)
        assertEquals(if (numbers) null else 12345, node.fields.single { it.name == "NUMBER" }.value)
      }
      URLClassLoader(arrayOf(output.toUri().toURL()), platformClassLoader()).use { loader ->
        val type = loader.loadClass("sample.App")
        assertEquals("field secret", type.getField("TEXT").get(null))
        assertEquals(12345, type.getField("NUMBER").get(null))
        assertEquals("method secret", type.getMethod("message").invoke(null))
        assertEquals(67890, type.getMethod("number").invoke(null))
      }
    }
  }

  @Test fun customStrategyAndPerPassRandomReachTheTransformers() {
    val keys = mutableListOf<Int>()
    var numericRandomCalls = 0
    val strategy = object : StringEncryptionStrategy {
      override fun encrypt(value: String, key: Int): EncryptedString {
        keys += key
        return XorStringEncryptionStrategy().encrypt(value, key)
      }
    }
    val output = dir.resolve("custom.jar")
    Obfuscator(config().copy(
      stringEncryptionStrategy = strategy,
      stringRandom = object : Random(0) { override fun nextInt() = 123 },
      numberRandom = object : Random(0) {
        override fun nextInt(): Int { numericRandomCalls++; return 456 }
      },
    )).obfuscate(input(), output)
    assertTrue(keys.isNotEmpty())
    assertTrue(keys.all { it == 123 })
    assertTrue(numericRandomCalls > 0)
    URLClassLoader(arrayOf(output.toUri().toURL()), platformClassLoader()).use { loader ->
      val type = loader.loadClass("sample.App")
      assertEquals("method secret", type.getMethod("message").invoke(null))
      assertEquals(67890, type.getMethod("number").invoke(null))
    }
  }
}