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
