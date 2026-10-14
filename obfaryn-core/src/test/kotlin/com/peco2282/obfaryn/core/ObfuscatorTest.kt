package com.peco2282.obfaryn.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.objectweb.asm.ClassReader
import org.objectweb.asm.util.CheckClassAdapter
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ObfuscatorTest {
  @TempDir lateinit var dir: Path

  @Test fun javaJarRunsAndHidesLiterals() {
    val source = dir.resolve("src/sample/App.java")
    Files.createDirectories(source.parent)
    source.writeText("""
      package sample;
      interface Greeter { String speak(); default String marker() { return "IFace"; } }
      class Base { String value() { return "base"; } }
      class Impl extends Base implements Greeter {
        public String speak() { return "Hello World"; }
        @Override String value() { return super.value() + 42; }
      }
      enum Choice implements Greeter { ONE("enum"); private final String label; Choice(String label) { this.label = label; } public String speak() { return label; } }
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
      @interface Tag { String value(); }
      @Tag("marked") class Tagged {}
      class Host { public static final Companion Companion = new Companion(); static class Companion { String value() { return "companion"; } } }
      class SerializableGreeter implements Greeter, java.io.Serializable { public String speak() { return "serial"; } }
      class Runner implements Runnable { public void run() {} }
      interface Calc { int calc(int x); }
      interface Generic<T> { T value(); }
      class StringGeneric implements Generic<String> { public String value() { return "G"; } }
      class Util {
        static int magic = 42;
        static int calc(int x) { return x + magic; }
        static long big() { return 9876543210L; }
        static float fraction() { return 3.5f; }
        static double decimal() { return -4.25d; }
      }
      public class App {
        public static String result() { new Runner().run(); Calc calc = n -> n + 2; return new Impl().speak() + ":" + Choice.ONE.speak() + ":" + Host.Companion.value() + ":" + new SerializableGreeter().speak() + ":" + new Impl().marker() + ":" + new Impl().value() + ":" + Util.calc(3) + ":" + Util.big() + ":" + Util.fraction() + ":" + Util.decimal() + ":" + calc.calc(3) + ":" + ((Generic<String>)new StringGeneric()).value(); }
        public static void main(String[] args) { System.out.println(result()); }
      }
    """.trimIndent())
    val classes = dir.resolve("classes")
    Files.createDirectories(classes)
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
    val input = dir.resolve("input.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      Files.walk(classes).use { files -> files.filter(Files::isRegularFile).forEach { file ->
        val name = classes.relativize(file).toString().replace('\\', '/')
        jar.putNextEntry(JarEntry(name)); jar.write(Files.readAllBytes(file)); jar.closeEntry()
      } }
      jar.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
      jar.write("Manifest-Version: 1.0\r\nMain-Class: sample.App\r\n\r\n".toByteArray())
      jar.closeEntry()
      jar.putNextEntry(JarEntry("META-INF/TEST.SF")); jar.write("invalid signature".toByteArray()); jar.closeEntry()
    }
    val output = dir.resolve("output.jar")
    val map = dir.resolve("mapping.txt")
    val result = Obfuscator(ObfuscationConfig(seed = 19, verifyOutput = true)).obfuscate(input, output, map)
    assertTrue(result.stringsEncrypted > 0)
    assertTrue(result.constantsObfuscated > 0)
    assertTrue(result.classesRenamed > 0)
    assertTrue(result.methodsRenamed > 0)
    assertTrue(result.fieldsRenamed > 0)
    assertTrue(map.readText().contains("sample/Impl"))
    val lines = Files.readAllLines(map)
    fun mapped(owner: String, member: String) = lines.single { it.startsWith("  $owner.$member -> ") }.substringAfter(" -> ")
    val renamedChoice = lines.single { it.startsWith("sample/Choice -> ") }.substringAfter(" -> ")
    assertNotEquals("sample/Choice", renamedChoice)
    val renamedTag = lines.single { it.startsWith("sample/Tag -> ") }.substringAfter(" -> ")
    val renamedTagged = lines.single { it.startsWith("sample/Tagged -> ") }.substringAfter(" -> ")
    assertNotEquals("sample/Tag", renamedTag)
    assertFalse(lines.any { it.startsWith("  sample/Tag.value()Ljava/lang/String; -> ") })
    assertEquals(mapped("sample/Greeter", "speak()Ljava/lang/String;"), mapped("sample/Impl", "speak()Ljava/lang/String;"))
    assertEquals(mapped("sample/Greeter", "speak()Ljava/lang/String;"), mapped("sample/Choice", "speak()Ljava/lang/String;"))
    assertEquals(mapped("sample/Greeter", "speak()Ljava/lang/String;"), mapped("sample/SerializableGreeter", "speak()Ljava/lang/String;"))
    assertEquals(mapped("sample/Base", "value()Ljava/lang/String;"), mapped("sample/Impl", "value()Ljava/lang/String;"))
    assertTrue(lines.any { it.startsWith("  sample/Greeter.marker()Ljava/lang/String; -> ") })
    assertTrue(lines.any { it.startsWith("  sample/Calc.calc(I)I -> ") })
    assertEquals(mapped("sample/Generic", "value()Ljava/lang/Object;"), mapped("sample/StringGeneric", "value()Ljava/lang/Object;"))
    assertTrue(lines.any { it.startsWith("  sample/StringGeneric.value()Ljava/lang/String; -> ") })
    assertFalse(lines.any { it.startsWith("  sample/Runner.run()V -> ") })
    assertTrue(lines.any { it.startsWith("  sample/Choice.label:Ljava/lang/String; -> ") })
    assertFalse(lines.any { it.startsWith("  sample/Choice.ONE:Lsample/Choice; -> ") })
    assertTrue(lines.any { it.startsWith("  sample/Host.Companion:Lsample/Host\$Companion; -> ") })
    JarFile(output.toFile()).use { jar ->
      val bytes = jar.entries().asSequence().filter { it.name.endsWith(".class") }
        .flatMap { jar.getInputStream(it).readBytes().asSequence() }.toList().toByteArray()
      assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Hello World"))
      assertNotNull(jar.getJarEntry("META-INF/MANIFEST.MF"))
      assertNull(jar.getJarEntry("META-INF/TEST.SF"))
    }
    URLClassLoader(arrayOf(output.toUri().toURL()), platformClassLoader()).use { loader ->
      val actual = loader.loadClass("sample.App").getMethod("result").invoke(null)
      assertEquals("Hello World:enum:companion:serial:IFace:base42:45:9876543210:3.5:-4.25:5:G", actual)
      val choice = loader.loadClass(renamedChoice.replace('/', '.'))
      assertEquals("ONE", choice.enumConstants.single().toString())
      val valueOf = choice.getDeclaredMethod("valueOf", String::class.java).apply { isAccessible = true }
      assertSame(choice.enumConstants.single(), valueOf.invoke(null, "ONE"))
      val annotation = loader.loadClass(renamedTagged.replace('/', '.')).declaredAnnotations.single()
      assertEquals(renamedTag.replace('/', '.'), annotation.annotationClass.java.name)
      val annotationValue = annotation.annotationClass.java.getDeclaredMethod("value").apply { isAccessible = true }
      assertEquals("marked", annotationValue.invoke(annotation))
      JarFile(output.toFile()).use { jar ->
        for (entry in jar.entries().asSequence().filter { it.name.endsWith(".class") }) {
          val errors = StringWriter()
          CheckClassAdapter.verify(ClassReader(jar.getInputStream(entry)), loader, false, PrintWriter(errors))
          assertEquals("", errors.toString(), entry.name)
        }
      }
    }
    val output2 = dir.resolve("output2.jar")
    Obfuscator(ObfuscationConfig(seed = 19)).obfuscate(input, output2)
    assertArrayEquals(Files.readAllBytes(output), Files.readAllBytes(output2))
    val paper = dir.resolve("paper.jar")
    JarOutputStream(Files.newOutputStream(paper)).use { target ->
      JarFile(input.toFile()).use { sourceJar ->
        for (entry in sourceJar.entries().asSequence()) {
          target.putNextEntry(JarEntry(entry.name))
          target.write(sourceJar.getInputStream(entry).readBytes())
          target.closeEntry()
        }
      }
      target.putNextEntry(JarEntry("paper-plugin.yml"))
      target.write("name: Test\nmain: sample.App\nbootstrapper: sample.Impl\n".toByteArray())
      target.closeEntry()
    }
    val paperOut = dir.resolve("paper-out.jar")
    val paperResult = Obfuscator(ObfuscationConfig(seed = 19, platform = "paper")).obfuscate(paper, paperOut)
    assertEquals("paper", paperResult.platform)
    JarFile(paperOut.toFile()).use { jar ->
      assertNotNull(jar.getJarEntry("sample/Impl.class"))
      assertNotNull(jar.getJarEntry("paper-plugin.yml"))
    }
  }

  @Test fun serviceLoaderAndReflectionRemainUsable() {
    val sourceRoot = dir.resolve("service-src/sample")
    Files.createDirectories(sourceRoot)
    sourceRoot.resolve("Service.java").writeText("package sample; public interface Service { String name(); }")
    sourceRoot.resolve("Provider.java").writeText("package sample; public class Provider implements Service { public String name() { return \"provider\"; } }")
    sourceRoot.resolve("Reflect.java").writeText("package sample; public class Reflect { public static String call() throws Exception { Class<?> c = Class.forName(\"sample.Target\"); return (String)c.getDeclaredMethod(\"value\", new Class<?>[0]).invoke(c.getConstructor().newInstance()); } }")
    sourceRoot.resolve("Target.java").writeText("package sample; public class Target { public String value() { return \"reflected\"; } }")
    val classes = dir.resolve("service-classes")
    Files.createDirectories(classes)
    val sources = Files.list(sourceRoot).use { it.map(Path::toString).unmodifiableList() }
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-d", classes.toString(), *sources.toTypedArray()))
    val input = dir.resolve("service.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      Files.list(classes.resolve("sample")).use { files -> files.forEach { file ->
        jar.putNextEntry(JarEntry("sample/${file.fileName}")); jar.write(Files.readAllBytes(file)); jar.closeEntry()
      } }
      jar.putNextEntry(JarEntry("META-INF/services/sample.Service"))
      jar.write("sample.Provider\n".toByteArray()); jar.closeEntry()
    }
    val output = dir.resolve("service-out.jar")
    val result = Obfuscator(ObfuscationConfig(seed = 31, verifyOutput = true, keep = listOf("sample.Reflect"))).obfuscate(input, output)
    assertTrue(result.warnings.any { "sample.Target" in it })
    assertTrue(result.warnings.any { "value" in it })
    URLClassLoader(arrayOf(output.toUri().toURL()), platformClassLoader()).use { loader ->
      val service = loader.loadClass("sample.Service")
      val provider = java.util.ServiceLoader.load(service, loader).iterator().next()
      assertEquals("provider", service.getMethod("name").invoke(provider))
      assertEquals("reflected", loader.loadClass("sample.Reflect").getMethod("call").invoke(null))
    }
  }

  @Test fun kotlinJarRunsAfterTransformation() {
    val root = Paths.get(requireNotNull(javaClass.classLoader.getResource("fixture")).toURI())
    val input = dir.resolve("kotlin.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      Files.list(root).use { files -> files.filter { it.fileName.toString().endsWith(".class") }.forEach { file ->
        jar.putNextEntry(JarEntry("fixture/${file.fileName}"))
        jar.write(Files.readAllBytes(file))
        jar.closeEntry()
      } }
      jar.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
      jar.write("Manifest-Version: 1.0\r\nMain-Class: fixture.KotlinSample\r\n\r\n".toByteArray())
      jar.closeEntry()
    }
    val output = dir.resolve("kotlin-out.jar")
    val result = Obfuscator(ObfuscationConfig(seed = 7, verifyOutput = true)).obfuscate(input, output)
    assertTrue(result.stringsEncrypted > 0)
    assertTrue(result.classesRenamed >= 2)
    assertTrue(requireNotNull(result.mapping).readText().contains("fixture/KotlinData ->"))
    assertTrue(requireNotNull(result.mapping).readText().contains("fixture/KotlinSample.secret()Ljava/lang/String; ->"))
    assertTrue(result.warnings.any { it.contains("AUTO stripped kotlin.Metadata") })
    val preserved = Obfuscator(ObfuscationConfig(seed = 7, kotlinMetadataMode = KotlinMetadataMode.PRESERVE))
      .obfuscate(input, dir.resolve("kotlin-preserved.jar"))
    assertEquals(0, preserved.classesRenamed)
    URLClassLoader(arrayOf(output.toUri().toURL()), javaClass.classLoader).use {
      val loader = object : URLClassLoader(arrayOf(output.toUri().toURL()), javaClass.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
          if (name.startsWith("fixture.")) {
            synchronized(getClassLoadingLock(name)) {
              val loaded = findLoadedClass(name) ?: findClass(name)
              if (resolve) resolveClass(loaded)
              return loaded
            }
          }
          return super.loadClass(name, resolve)
        }
      }
      loader.use {
        val type = it.loadClass("fixture.KotlinSample")
        val instance = type.getConstructor().newInstance()
        assertEquals("Hello World!7Long", type.getMethod("run", String::class.java).invoke(instance, "Hello"))
        assertEquals("Hello World!7Long",
          type.getMethod("run\$default", type, String::class.java, Int::class.javaPrimitiveType, Any::class.java)(null, instance, null, 1, null)
        )
      }
    }
  }
}
