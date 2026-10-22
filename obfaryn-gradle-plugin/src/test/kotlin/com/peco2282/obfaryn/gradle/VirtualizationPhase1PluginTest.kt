package com.peco2282.obfaryn.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class VirtualizationPhase1PluginTest {
  @TempDir lateinit var dir: Path

  @Test fun defaultsAreStrictAndNotAnalyzeOnly() {
    val extension = ObfarynExtension()
    assertEquals("strict", extension.virtualization.mode)
    assertFalse(extension.virtualization.analyzeOnly)
    assertFalse(extension.flattenPackages)
  }

  @Test fun patternDslRequiresKotlinOptInAndRecommendsAnnotation() {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"")
    Files.writeString(dir.resolve("build.gradle.kts"), """
      plugins {
        java
        id("com.peco2282.obfaryn")
      }
      obfaryn {
        virtualize {
          methods = listOf("example/.*")
        }
      }
    """.trimIndent())
    val result = GradleRunner.create().withProjectDir(dir.toFile()).withArguments("help", "--stacktrace")
      .withPluginClasspath().buildAndFail()
    assertTrue(result.output.contains("Pattern-based virtualization is experimental"))
    assertTrue(result.output.contains("Prefer @Virtualize"))
  }

  @Test fun analyzeOnlyPropagatesAndWritesReportWithoutObfuscatedArtifacts() {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"")
    Files.writeString(dir.resolve("build.gradle.kts"), """
      @file:OptIn(com.peco2282.obfaryn.gradle.VirtualizeOptIn::class)

      plugins {
        java
        id("com.peco2282.obfaryn")
      }
      obfaryn {
        platform = "generic"
        virtualize {
          methods = listOf("example/Calc\\.(good|bad).*")
          mode = "strict"
          analyzeOnly = true
          report = "analysis.json"
        }
      }
    """.trimIndent())
    val source = dir.resolve("src/main/java/example/Calc.java")
    Files.createDirectories(source.parent)
    Files.writeString(source, "package example; public class Calc { public static int good() { return 1; } public static int bad(int v) { synchronized (Calc.class) { return v + 2; } } }")
    val result = GradleRunner.create().withProjectDir(dir.toFile()).withArguments("obfuscateJar", "--stacktrace")
      .withPluginClasspath().build()
    assertTrue(result.output.contains("BUILD SUCCESSFUL"))
    val json = Files.readString(dir.resolve("analysis.json"))
    assertTrue(json.contains("\"selected\":2"))
    assertTrue(json.contains("\"eligible\":1"))
    assertTrue(json.contains("\"rejected\":1"))
    assertFalse(Files.exists(dir.resolve("build/libs/sample-obfuscated.jar")))
    assertFalse(Files.exists(dir.resolve("build/libs/sample-obfuscated.mapping.txt")))
  }
}
