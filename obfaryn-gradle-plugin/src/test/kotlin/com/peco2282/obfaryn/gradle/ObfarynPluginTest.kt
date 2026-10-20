package com.peco2282.obfaryn.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

class ObfarynPluginTest {
  @TempDir lateinit var dir: Path

  @Test fun nestedOptionsShareLegacyValues() {
    val extension = ObfarynExtension()
    extension.string { it.enabled = false; it.includeConstantFields = false }
    extension.number { it.enabled = false }
    extension.metadata {
      it.stripDebugInfo = false
      it.stripKotlinMetadata = true
      it.kotlinMetadataMode = "preserve"
      it.stripGenericSignatures = true
      it.stripMethodParameters = false
    }
    assertFalse(extension.stringEncryption)
    assertFalse(extension.numberObfuscation)
    assertFalse(extension.stripDebugInfo)
    assertTrue(extension.stripKotlinMetadata)
    assertEquals("preserve", extension.kotlinMetadataMode)
    assertTrue(extension.stripGenericSignatures)
    assertFalse(extension.stripMethodParameters)
    extension.stringEncryption = true
    extension.stripDebugInfo = true
    assertTrue(extension.string.enabled)
    assertTrue(extension.metadata.stripDebugInfo)
  }

  @Test fun nestedDslConfiguresTransformationsAndOutputPaths() {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"")
    Files.writeString(dir.resolve("build.gradle.kts"), """
      import com.peco2282.obfaryn.api.transformer.StringEncryptionResult
      import com.peco2282.obfaryn.api.transformer.StringEncryptionCodec
      import com.peco2282.obfaryn.transformer.XorStringEncryptionCodec
      import java.util.Random
      import com.peco2282.obfaryn.api.ObfuscationContext
      import com.peco2282.obfaryn.api.transformer.Transformer
      plugins { java; id("com.peco2282.obfaryn") }
      tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }
      obfaryn {
        platform = "generic"
        seed = 4L
        applicationPackages = listOf("example")
        pipeline {
          add(object : Transformer {
            override val id = "custom-marker"
            override val before = setOf("string-encryption")
            override val requiresFrameRecomputation = true
            override fun transform(context: ObfuscationContext) {
              project.file("pipeline-used").writeText("yes")
            }
          })
        }
        string {
          enabled = true
          includeConstantFields = false
          random = Random(12)
          strategy = object : StringEncryptionCodec by XorStringEncryptionCodec() {
            override fun encrypt(value: String, key: Int): StringEncryptionResult {
              project.file("strategy-used").writeText("yes")
              return XorStringEncryptionCodec().encrypt(value, key)
            }
          }
        }
        number { enabled = false; includeConstantFields = false }
        metadata {
          stripDebugInfo = false
          kotlinMetadataMode = "preserve"
          stripGenericSignatures = true
          stripMethodParameters = true
        }
        rename { classes = false; methods = false; fields = false }
        output {
          directory = "build/protected jars"
          fileName = "release.jar"
          mapping = "build/maps/release.txt"
        }
      }
    """.trimIndent())
    val source = dir.resolve("src/main/java/example/App.java")
    Files.createDirectories(source.parent)
    Files.writeString(source, """
      package example;
      public class App<T> {
        public static final String TEXT = "field secret";
        public static String message(String input) { return "method secret"; }
      }
    """.trimIndent())
    val runner = GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath()
      .withArguments("obfuscateJar", "--stacktrace")
    runner.build()
    val output = dir.resolve("build/protected jars/release.jar")
    assertTrue(Files.exists(output))
    assertTrue(Files.exists(dir.resolve("build/maps/release.txt")))
    assertTrue(Files.exists(dir.resolve("strategy-used")))
    assertTrue(Files.exists(dir.resolve("pipeline-used")))
    assertFalse(Files.exists(dir.resolve("build/libs/sample-obfuscated.jar")))
    JarFile(output.toFile()).use { jar ->
      val bytes = jar.getInputStream(jar.getJarEntry("example/App.class")).readBytes()
      val text = String(bytes, Charsets.ISO_8859_1)
      assertTrue(text.contains("field secret"))
      assertFalse(text.contains("method secret"))
      assertTrue(text.contains("SourceFile"))
    }
    java.net.URLClassLoader(arrayOf(output.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
      val type = loader.loadClass("example.App")
      assertEquals(0, type.typeParameters.size)
      val method = type.getMethod("message", String::class.java)
      assertFalse(method.parameters.single().isNamePresent)
      assertEquals("method secret", method.invoke(null, "ignored"))
    }
    val script = dir.resolve("build.gradle.kts")
    Files.writeString(script, Files.readString(script).replace(
      "directory = \"build/protected jars\"", "file = \"build/explicit/output.jar\""))
    runner.build()
    assertTrue(Files.exists(dir.resolve("build/explicit/output.jar")))
  }

  @Test fun groovyDslAcceptsNestedBlocks() {
    Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'sample'")
    Files.writeString(dir.resolve("build.gradle"), """
      plugins { id 'java'; id 'com.peco2282.obfaryn' }
      obfaryn {
        string { enabled = false; includeConstantFields = false }
        number { enabled = false }
        metadata { stripDebugInfo = false }
        output { directory = 'build/secure'; fileName = 'app.jar' }
      }
    """.trimIndent())
    GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath()
      .withArguments("obfuscateJar", "--stacktrace").build()
    assertTrue(Files.exists(dir.resolve("build/secure/app.jar")))
    assertTrue(Files.exists(dir.resolve("build/secure/app.mapping.txt")))
  }

  @Test fun outputCannotOverwriteTheInputOrMapping() {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"")
    val script = dir.resolve("build.gradle.kts")
    Files.writeString(script, """
      plugins { java; id("com.peco2282.obfaryn") }
      obfaryn { output { file = "build/libs/sample.jar" } }
    """.trimIndent())
    val runner = GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath()
      .withArguments("obfuscateJar", "--stacktrace")
    assertTrue(runner.buildAndFail().output.contains("must use different paths"))
    JarFile(dir.resolve("build/libs/sample.jar").toFile()).use { jar ->
      assertNotNull(jar.getJarEntry("META-INF/MANIFEST.MF"))
    }
    Files.writeString(script, """
      plugins { java; id("com.peco2282.obfaryn") }
      obfaryn { output { file = "build/result.jar"; mapping = "build/result.jar" } }
    """.trimIndent())
    assertTrue(runner.buildAndFail().output.contains("must use different paths"))
    assertFalse(Files.exists(dir.resolve("build/result.jar")))
  }

  @Test fun producesObfuscatedJarAndMapping() {
    val parentSource = dir.resolve("dependency-src/dep/Parent.java")
    Files.createDirectories(parentSource.parent)
    Files.writeString(parentSource, "package dep; public class Parent { public String inherited() { return \"parent\"; } }")
    val parentClasses = dir.resolve("dependency-classes")
    Files.createDirectories(parentClasses)
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
      "-d", parentClasses.toString(), parentSource.toString()))
    val dependency = dir.resolve("dependency.jar")
    JarOutputStream(Files.newOutputStream(dependency)).use { jar ->
      jar.putNextEntry(JarEntry("dep/Parent.class"))
      jar.write(Files.readAllBytes(parentClasses.resolve("dep/Parent.class")))
      jar.closeEntry()
    }
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"")
    Files.writeString(dir.resolve("build.gradle.kts"), """
      plugins {
        java
        id("com.peco2282.obfaryn")
      }
      dependencies { compileOnly(files("dependency.jar")) }
      obfaryn {
        platform = "generic"
        seed = 4L
        recomputeFrames = false
        rename { classes = true; methods = true; fields = true }
      }
    """.trimIndent())
    val source = dir.resolve("src/main/java/example/App.java")
    Files.createDirectories(source.parent)
    Files.writeString(source, "package example; public class App { public static String message() { return \"Secret\"; } }")
    Files.writeString(source.parent.resolve("Child.java"),
      "package example; public class Child extends dep.Parent { @Override public String inherited() { return super.inherited(); } public String own() { return \"own\"; } }")
    val result = GradleRunner.create().withProjectDir(dir.toFile()).withArguments("obfuscateJar", "--stacktrace")
      .withPluginClasspath().build()
    assertTrue(result.output.contains("BUILD SUCCESSFUL"))
    val output = dir.resolve("build/libs/sample-obfuscated.jar")
    assertTrue(Files.exists(output))
    val mapping = dir.resolve("build/libs/sample-obfuscated.mapping.txt")
    assertTrue(Files.exists(mapping))
    assertTrue(Files.readString(mapping).contains("example/Child.own()Ljava/lang/String; ->"))
    assertFalse(Files.readString(mapping).contains("example/Child.inherited()Ljava/lang/String; ->"))
    JarFile(output.toFile()).use { jar ->
      val contents = jar.entries().asSequence().filter { it.name.endsWith(".class") }
        .flatMap { jar.getInputStream(it).readBytes().asSequence() }.toList().toByteArray()
      assertFalse(String(contents, Charsets.ISO_8859_1).contains("Secret"))
    }
  }
}
