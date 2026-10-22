package com.peco2282.obfaryn.cli

import com.peco2282.obfaryn.core.platformClassLoader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.readText
import kotlin.io.path.writeText

class VirtualizationPhase1CliTest {
  @TempDir lateinit var dir: Path

  private fun input(): Path {
    val source = dir.resolve("Calc.java")
    source.writeText("public class Calc { public static int good() { return 1; } public static int bad(int v) { synchronized (Calc.class) { return v + 2; } } }")
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dir.toString(), source.toString()))
    val input = dir.resolve("input.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      jar.putNextEntry(JarEntry("Calc.class"))
      jar.write(Files.readAllBytes(dir.resolve("Calc.class")))
      jar.closeEntry()
    }
    return input
  }

  @Test fun analyzeOnlyIsIndependentOfStrictSelectionAndNeedsNoOutput() {
    val input = input()
    val report = dir.resolve("analysis.json")
    val mapping = dir.resolve("mapping.txt")
    main(arrayOf(input.toString(), "--virtualize-method", "Calc\\.(good|bad).*",
      "--virtualization-mode", "strict", "--virtualization-analyze-only",
      "--virtualization-report", report.toString(), "--mapping", mapping.toString()))
    val json = report.readText()
    assertTrue(json.contains("\"selected\":2"))
    assertTrue(json.contains("\"eligible\":1"))
    assertTrue(json.contains("\"rejected\":1"))
    assertTrue(json.contains("\"reason\":\"UNSUPPORTED_OPCODE\""))
    assertFalse(Files.exists(dir.resolve("input-obfuscated.jar")))
    assertFalse(Files.exists(mapping))
  }

  @Test fun eligibleSelectionSkipsUnsupportedMethodAndWritesOutput() {
    val input = input()
    val output = dir.resolve("output.jar")
    val report = dir.resolve("analysis.json")
    main(arrayOf(input.toString(), "-o", output.toString(), "--virtualize-method", "Calc\\.(good|bad).*",
      "--virtualization-mode", "eligible", "--virtualization-report", report.toString(),
      "--no-rename", "--no-string-encryption", "--no-number-obfuscation", "--seed", "4"))
    assertTrue(Files.exists(output))
    assertTrue(report.readText().contains("\"rejected\":1"))
    java.net.URLClassLoader(arrayOf(output.toUri().toURL()), platformClassLoader()).use {
      val calc = it.loadClass("Calc")
      assertEquals(1, calc.getMethod("good").invoke(null))
      assertEquals(2, calc.getMethod("bad", Int::class.javaPrimitiveType).invoke(null, 0))
    }
  }
}
