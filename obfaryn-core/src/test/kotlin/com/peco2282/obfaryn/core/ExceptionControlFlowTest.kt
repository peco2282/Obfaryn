package com.peco2282.obfaryn.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.JumpInsnNode
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.writeText

class ExceptionControlFlowTest {
  @TempDir lateinit var dir: Path

  @Test fun reorderedBlocksPreserveNestedHandlersFinallyAndMonitors() {
    val source = dir.resolve("Guarded.java")
    source.writeText("""
      package app;
      public class Guarded {
        public static int trace;
        public static int run(int n) {
          trace = 0;
          synchronized (Guarded.class) {
            try {
              try {
                if (n == -1) throw new IllegalArgumentException();
                if (n == -2) throw new IllegalStateException();
                return 100 / n;
              } catch (IllegalArgumentException e) { trace += 2; return 22; }
              finally { trace += 3; }
            } catch (RuntimeException e) { trace += 5; return 33; }
            finally { trace += 7; }
          }
        }
        public static int loop(int n) {
          int total = 0;
          for (int i = -3; i <= n; i++) {
            try { total += 10 / i; }
            catch (ArithmeticException e) { total += 17; }
          }
          return total;
        }
      }
    """.trimIndent())
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
      "--release", "8", "-d", dir.toString(), source.toString()))
    val input = dir.resolve("input.jar")
    JarOutputStream(Files.newOutputStream(input)).use {
      it.putNextEntry(JarEntry("app/Guarded.class"))
      it.write(Files.readAllBytes(dir.resolve("app/Guarded.class"))); it.closeEntry()
    }
    for (lightweight in listOf(false, true)) for (seed in 0L..5L) {
      val output = dir.resolve("out-$lightweight-$seed.jar")
      val result = Obfuscator(ObfuscationConfig(
        controlFlow = true, lightweightControlFlow = lightweight, seed = seed, verifyOutput = true,
        stringEncryption = false, numberObfuscation = false,
        renameClasses = false, renameFields = false, renameMethods = false,
      )).obfuscate(input, output)
      assertTrue(result.transformerReports.single { it.id == "control-flow" }.metrics.getValue("rearrangedMethods") >= 2)
      JarFile(output.toFile()).use { jar ->
        val node = ClassNode()
        ClassReader(jar.getInputStream(jar.getJarEntry("app/Guarded.class"))).accept(node, 0)
        val method = node.methods.single { it.name == "run" }
        assertTrue(method.tryCatchBlocks.isNotEmpty())
        assertTrue(method.instructions.toArray().filterIsInstance<JumpInsnNode>().size > 5)
      }
      URLClassLoader(arrayOf(input.toUri().toURL()), null).use { baseline ->
        URLClassLoader(arrayOf(output.toUri().toURL()), null).use { transformed ->
          val original = baseline.loadClass("app.Guarded")
          val actual = transformed.loadClass("app.Guarded")
          for (n in listOf(-3, -2, -1, 0, 1, 4, 11)) {
            for (method in listOf("run", "loop")) {
              assertEquals(original.getMethod(method, Int::class.javaPrimitiveType).invoke(null, n),
                actual.getMethod(method, Int::class.javaPrimitiveType).invoke(null, n), "$method($n), seed=$seed")
              assertEquals(original.getField("trace").getInt(null), actual.getField("trace").getInt(null))
            }
          }
        }
      }
    }
  }
}
