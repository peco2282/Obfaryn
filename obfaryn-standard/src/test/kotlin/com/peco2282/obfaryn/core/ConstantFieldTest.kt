package com.peco2282.obfaryn.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.writeText

class ConstantFieldTest {
  @TempDir lateinit var dir: Path

  @Test fun applicationConstantValuesBecomeRuntimeInitializers() {
    val source = dir.resolve("src/sample/App.java")
    Files.createDirectories(source.parent)
    source.writeText("""
      package sample;
      final class Fields {
        public static final String TEXT = "sensitive_tag";
        public static final int LIMIT = 8;
        public static final long LONG = 123456789L;
        public static final float FLOAT = -3.5f;
        public static final double DOUBLE = 0.7d;
      }
      public class App {
        public static String value() { return Fields.TEXT + Fields.LIMIT; }
        public static void main(String[] args) { System.out.println(value()); }
      }
    """.trimIndent())
    val classes = dir.resolve("classes")
    Files.createDirectories(classes)
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
      "--release", "17", "-d", classes.toString(), source.toString()))
    val input = dir.resolve("input.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      Files.list(classes.resolve("sample")).use { files -> files.forEach { file ->
        jar.putNextEntry(JarEntry("sample/${file.fileName}"))
        jar.write(Files.readAllBytes(file))
        jar.closeEntry()
      } }
      jar.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
      jar.write("Manifest-Version: 1.0\r\nMain-Class: sample.App\r\n\r\n".toByteArray())
      jar.closeEntry()
    }
    val output = dir.resolve("output.jar")
    val result = Obfuscator(ObfuscationConfig(seed = 42, verifyOutput = true)).obfuscate(input, output)
    val mapping = Files.readAllLines(requireNotNull(result.mapping))
    val mappedClass = mapping.single { it.startsWith("sample/Fields -> ") }.substringAfter(" -> ")
    fun mappedField(name: String, desc: String) = mapping.single {
      it.startsWith("  sample/Fields.$name:$desc -> ")
    }.substringAfter(" -> ")
    val fields = mapOf(
      "TEXT" to "Ljava/lang/String;", "LIMIT" to "I", "LONG" to "J", "FLOAT" to "F", "DOUBLE" to "D",
    )
    JarFile(output.toFile()).use { jar ->
      val bytes = jar.getInputStream(requireNotNull(jar.getJarEntry("$mappedClass.class"))).readBytes()
      val node = ClassNode()
      ClassReader(bytes).accept(node, 0)
      for ((name, desc) in fields) {
        val field = node.fields.single { it.name == mappedField(name, desc) }
        assertNull(field.value, name)
      }
      assertFalse(String(bytes, Charsets.ISO_8859_1).contains("sensitive_tag"))
    }
    URLClassLoader(arrayOf(output.toUri().toURL()), ClassLoader.getSystemClassLoader().parent).use { loader ->
      val type = loader.loadClass(mappedClass.replace('/', '.'))
      fun value(name: String, desc: String): Any? = type.getDeclaredField(mappedField(name, desc)).let {
        it.isAccessible = true
        it.get(null)
      }
      assertEquals("sensitive_tag", value("TEXT", "Ljava/lang/String;"))
      assertEquals(8, value("LIMIT", "I"))
      assertEquals(123456789L, value("LONG", "J"))
      assertEquals(-3.5f, value("FLOAT", "F"))
      assertEquals(0.7, value("DOUBLE", "D"))
    }
  }
}
