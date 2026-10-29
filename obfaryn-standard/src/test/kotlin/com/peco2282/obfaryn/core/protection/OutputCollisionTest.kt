package com.peco2282.obfaryn.core.protection

import com.peco2282.obfaryn.core.ObfuscationConfig
import com.peco2282.obfaryn.core.Obfuscator
import com.peco2282.obfaryn.core.fixtures.JavaJarFixture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OutputCollisionTest {
  @TempDir lateinit var dir: Path

  @Test fun reportsAndArtifactsCannotOverwriteInputDependenciesOrKeys() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Main" to
      "package app; public class Main { public static int value() { return 7; } }"))
    val original = Files.readAllBytes(input)
    val output = dir.resolve("result.jar")
    val dependency = dir.resolve("dependency.jar")
    Files.copy(input, dependency)
    val key = dir.resolve("key.der")
    Files.write(key, byteArrayOf(1, 2, 3))
    val configurations = listOf(
      ObfuscationConfig(virtualizationReport = input),
      ObfuscationConfig(virtualizationReport = dependency, classpath = listOf(dependency)),
      ObfuscationConfig(virtualizationReport = key, integrityPrivateKey = key, integrityPublicKey = key),
      ObfuscationConfig(classpath = listOf(output)),
      ObfuscationConfig(virtualizationReport = dir.resolve("result.mapping.txt")),
    )
    for (config in configurations) {
      assertThrows(IllegalArgumentException::class.java) { Obfuscator(config).obfuscate(input, output) }
      assertArrayEquals(original, Files.readAllBytes(input))
      assertArrayEquals(original, Files.readAllBytes(dependency))
      assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(key))
      assertFalse(Files.exists(output))
      assertFalse(Files.exists(dir.resolve("result.mapping.txt")))
    }
    assertThrows(IllegalArgumentException::class.java) { Obfuscator().obfuscate(input, output, input) }
    assertArrayEquals(original, Files.readAllBytes(input))
    assertFalse(Files.exists(output))
  }

  @Test fun existingFileAliasesAndFutureOutputsThroughDirectoryAliasesAreRejected() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Main" to
      "package app; public class Main { public static int value() { return 7; } }"))
    val original = Files.readAllBytes(input)
    val alias = dir.resolve("input-alias.jar")
    Files.createLink(alias, input)
    val output = dir.resolve("result.jar")
    assertThrows(IllegalArgumentException::class.java) {
      Obfuscator(ObfuscationConfig(virtualizationReport = alias)).obfuscate(input, output)
    }
    assertArrayEquals(original, Files.readAllBytes(input))
    assertFalse(Files.exists(output))

    val real = Files.createDirectory(dir.resolve("real"))
    val link = Files.createSymbolicLink(dir.resolve("alias"), real)
    val futureJar = real.resolve("future.jar")
    assertThrows(IllegalArgumentException::class.java) {
      Obfuscator(ObfuscationConfig(virtualizationReport = link.resolve("future.jar")))
        .obfuscate(input, futureJar)
    }
    assertFalse(Files.exists(futureJar))
    assertArrayEquals(original, Files.readAllBytes(input))

    val dangling = Files.createSymbolicLink(dir.resolve("future-alias.jar"), futureJar)
    assertThrows(IllegalArgumentException::class.java) {
      Obfuscator(ObfuscationConfig(virtualizationReport = dangling)).obfuscate(input, futureJar)
    }
    assertFalse(Files.exists(futureJar))
    assertArrayEquals(original, Files.readAllBytes(input))
  }

  @Test fun parentSegmentsAreResolvedAfterDirectorySymlinks() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Main" to
      "package app; public class Main { public static int value() { return 7; } }"))
    val real = Files.createDirectories(dir.resolve("nested/real"))
    val victim = dir.resolve("nested/input.jar")
    Files.copy(input, victim)
    val before = Files.readAllBytes(victim)
    val alias = Files.createSymbolicLink(dir.resolve("link"), real)
    assertThrows(IllegalArgumentException::class.java) {
      Obfuscator().obfuscate(victim, alias.resolve("../input.jar"))
    }
    assertArrayEquals(before, Files.readAllBytes(victim))
  }
}
