package com.peco2282.obfaryn.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.writeText

class CallIndirectionTest {
  @TempDir lateinit var dir: Path

  private fun input(): Path {
    val source = dir.resolve("Main.java")
    source.writeText("""
      package app;
      import com.peco2282.obfaryn.annotations.HotPath;
      import com.peco2282.obfaryn.annotations.NoObfuscation;
      class Excluded { static int value(int n) { return Math.abs(n); } }
      public class Main {
        interface Value { default long value(long x) { return x * 7; } }
        static class Impl implements Value {}
        private static int secret(int n) { return n * 13; }
        public static long calc(int n) {
          Value v = new Impl();
          return v.value(n) + secret(n) + Math.abs(n);
        }
        public static int caught(int n) {
          try { return Integer.parseInt(n == 0 ? "bad" : "17") / n; }
          catch (NumberFormatException e) { return -2; }
          catch (ArithmeticException e) { return -3; }
        }
        public static String instance(String v) { return v.trim().toUpperCase(java.util.Locale.ROOT); }
        public static synchronized double wide(double x, long y) { return Math.scalb(x, 2) + y; }
        public static Class<?> lookup() { return java.lang.invoke.MethodHandles.lookup().lookupClass(); }
        public static Class<?> load() throws Exception { return Class.forName("java.lang.String"); }
        @HotPath public static int hot(int n) { return Math.abs(n); }
        @NoObfuscation public static int plain(int n) { return Math.abs(n); }
      }
    """.trimIndent())
    val dependency = dir.resolve("Dependency.java")
    dependency.writeText("package library; public class Dependency { public static int value(int n) { return Math.abs(n); } }")
    val annotations = Class.forName("com.peco2282.obfaryn.annotations.HotPath").protectionDomain.codeSource.location.toURI()
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
      "--release", "8", "-classpath", java.nio.file.Paths.get(annotations).toString(), "-d", dir.toString(), source.toString(), dependency.toString()))
    return dir.resolve("input.jar").also { input ->
      JarOutputStream(Files.newOutputStream(input)).use { jar ->
        Files.walk(dir).use { paths ->
          paths.filter { it.toString().endsWith(".class") }.sorted().forEach { path ->
            jar.putNextEntry(JarEntry(dir.relativize(path).toString()))
            jar.write(Files.readAllBytes(path)); jar.closeEntry()
          }
        }
      }
    }
  }

  @Test fun encryptedLinkageRetainsBehaviorWithRenamingAndControlFlow() {
    val input = input()
    for (lightweight in listOf(false, true)) for (seed in 0L..2L) {
      val output = dir.resolve("out-$lightweight-$seed.jar")
      val result = Obfuscator(ObfuscationConfig(
        callIndirection = true, applicationPackages = listOf("app"),
        controlFlow = true, lightweightControlFlow = lightweight, seed = seed,
        keep = listOf("app.Main"), excludeClasses = listOf("app/Excluded"), verifyOutput = true,
      )).obfuscate(input, output)
      assertEquals(0, result.methodsVirtualized)
      assertTrue(result.classesRenamed > 0)
      assertTrue(result.transformerReports.single { it.id == "call-indirection" }.metrics.getValue("indirectedCalls") > 0)
      JarFile(output.toFile()).use { jar ->
        val node = ClassNode()
        ClassReader(jar.getInputStream(jar.getJarEntry("app/Main.class"))).accept(node, 0)
        fun sites(name: String) = node.methods.single { it.name == name }.instructions.toArray().filterIsInstance<InvokeDynamicInsnNode>()
        for (name in listOf("app/Excluded", "library/Dependency")) {
          val untouched = ClassNode()
          ClassReader(jar.getInputStream(jar.getJarEntry("$name.class"))).accept(untouched, 0)
          assertFalse(untouched.methods.any { m -> m.instructions.toArray().any { it is InvokeDynamicInsnNode } })
        }
        assertTrue(sites("calc").isNotEmpty())
        assertTrue(sites("hot").isEmpty())
        assertTrue(sites("plain").isEmpty())
        assertFalse(sites("calc").flatMap { it.bsmArgs.toList() }.any { it == "java/lang/Math" || it == "abs" })
      }
      URLClassLoader(arrayOf(output.toUri().toURL()), null).use { loader ->
        val cls = loader.loadClass("app.Main")
        val integer = Int::class.javaPrimitiveType
        for (n in listOf(-17, -1, 0, 1, 17)) {
          assertEquals(n.toLong() * 20 + kotlin.math.abs(n), cls.getMethod("calc", integer).invoke(null, n))
          assertEquals(if (n == 0) -2 else 17 / n, cls.getMethod("caught", integer).invoke(null, n))
        }
        assertEquals("HELLO", cls.getMethod("instance", String::class.java).invoke(null, " hello "))
        val error = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
          cls.getMethod("instance", String::class.java).invoke(null, null)
        }
        assertTrue(error.cause is NullPointerException)
        assertEquals(11.0, cls.getMethod("wide", Double::class.javaPrimitiveType, Long::class.javaPrimitiveType).invoke(null, 2.0, 3L))
        assertEquals(cls, cls.getMethod("lookup").invoke(null))
        assertEquals(String::class.java, cls.getMethod("load").invoke(null))
      }
    }
  }

  @Test fun classfileLimitsLeaveSomeCallsDirectAndKeepTheJarExecutable() {
    for (largePool in listOf(true, false)) {
      val writer = ClassWriter(0)
      writer.visit(V1_8, ACC_PUBLIC, "app/Large", null, "java/lang/Object", null)
      if (largePool) repeat(32500) { index ->
        writer.visitField(ACC_PUBLIC or ACC_STATIC or ACC_FINAL, "f$index", "Ljava/lang/String;", null, "f$index").visitEnd()
      }
      val method = writer.visitMethod(ACC_PUBLIC or ACC_STATIC, "calc", "(I)I", null, null)
      method.visitCode()
      if (!largePool) repeat(64900) { method.visitInsn(NOP) }
      repeat(100) {
        method.visitVarInsn(ILOAD, 0)
        method.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false)
        method.visitInsn(POP)
      }
      method.visitVarInsn(ILOAD, 0); method.visitInsn(IRETURN)
      method.visitMaxs(1, 1); method.visitEnd(); writer.visitEnd()
      val input = dir.resolve("large-$largePool.jar")
      JarOutputStream(Files.newOutputStream(input)).use {
        it.putNextEntry(JarEntry("app/Large.class")); it.write(writer.toByteArray()); it.closeEntry()
      }
      val output = dir.resolve("large-$largePool-output.jar")
      val result = Obfuscator(ObfuscationConfig(
        callIndirection = true, applicationPackages = listOf("app"), seed = 2,
        renameClasses = false, renameMethods = false, renameFields = false,
        stringEncryption = false, numberObfuscation = false, verifyOutput = true,
      )).obfuscate(input, output)
      val calls = result.transformerReports.single { it.id == "call-indirection" }.metrics.getValue("indirectedCalls")
      assertTrue(calls in 1L..99L, "Some calls must stay direct to respect classfile limits")
      URLClassLoader(arrayOf(output.toUri().toURL()), null).use { loader ->
        assertEquals(-6, loader.loadClass("app.Large").getMethod("calc", Int::class.javaPrimitiveType).invoke(null, -6))
      }
    }
  }

  @Test fun requiresApplicationScope() {
    assertThrows(IllegalArgumentException::class.java) {
      Obfuscator(ObfuscationConfig(callIndirection = true)).obfuscate(input(), dir.resolve("bad.jar"))
    }
  }
}
