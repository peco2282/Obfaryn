package com.peco2282.obfaryn.cli

import com.peco2282.obfaryn.core.platformClassLoader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.readText
import kotlin.io.path.writeText

class CliTest {
  @TempDir lateinit var dir: Path

  @Test fun cliWritesRunnableJar() {
    val source = dir.resolve("Main.java")
    source.writeText("public class Main { public static String value() { return \"CLI secret\"; } }")
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dir.toString(), source.toString()))
    val input = dir.resolve("in.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      jar.putNextEntry(JarEntry("Main.class"))
      jar.write(Files.readAllBytes(dir.resolve("Main.class")))
      jar.closeEntry()
    }
    val output = dir.resolve("out.jar")
    val dryOutput = dir.resolve("dry.jar")
    val report = dir.resolve("report.json")
    main(arrayOf(input.toString(), "--dry-run", "-o", dryOutput.toString(), "--seed", "10", "--report", report.toString()))
    assertFalse(Files.exists(dryOutput))
    assertTrue(report.readText().contains("\"dryRun\": true"))
    main(arrayOf(input.toString(), "-o", output.toString(), "--seed", "10", "--no-rename", "--recompute-frames", "--verify-output"))
    JarFile(output.toFile()).use { jar -> assertNotNull(jar.getJarEntry("Main.class")) }
    java.net.URLClassLoader(arrayOf(output.toUri().toURL()), platformClassLoader()).use {
      assertEquals("CLI secret", it.loadClass("Main").getMethod("value").invoke(null))
    }
  }
}
