package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.transformer.VirtualizationTransformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class VirtualKotlinFixture {
  @JvmField var number: Int = 7
  @JvmField var reference: Any? = null

  fun calculate(value: Int): Int = value * 3 + number
  fun identity(value: Any): Any = value
  fun nullable(value: Any?): Any? = value
  fun self(): Any? = this
  fun nothing(): Any? = null
  fun read(): Int = number
  fun write(value: Int): Int { number = value; return number }
  fun references(value: Any?): Any? { reference = value; return reference }
  fun invoking(value: Int): Int = helper(value)
  private fun helper(value: Int): Int = 100 / value
}

class VirtualKotlinTest {
  @TempDir lateinit var dir: Path

  @Test fun compiledKotlinFixturePreservesResultsIdentityAndExceptions() {
    val original = VirtualKotlinFixture::class.java
    val resource = "/${original.name.replace('.', '/')}.class"
    val compiled = original.getResourceAsStream(resource)!!.use { it.readBytes() }
    val selected = listOf("calculate", "identity", "nullable", "self", "nothing", "read", "write", "references", "invoking")
    for (seed in listOf(42L, 43L)) {
      val node = ClassNode()
      ClassReader(compiled).accept(node, 0)
      assertTrue(node.methods.single { it.name == "identity" }.instructions.toArray().any {
        it is org.objectweb.asm.tree.MethodInsnNode && it.owner == "kotlin/jvm/internal/Intrinsics" &&
          it.name == "checkNotNullParameter"
      })
      val context = ObfuscationContext(linkedMapOf(node.name to node), linkedMapOf())
      val transformer = VirtualizationTransformer(
        listOf("${Regex.escape(node.name)}\\.(${selected.joinToString("|")})\\(.*"), Random(seed), 2)
      transformer.transform(context)
      assertEquals(selected.size, transformer.count)
      val jar = dir.resolve("kotlin-$seed.jar")
      JarOutputStream(Files.newOutputStream(jar)).use { out ->
        for (type in context.classes.values) {
          val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
          type.accept(writer)
          out.putNextEntry(JarEntry("${type.name}.class"))
          out.write(writer.toByteArray())
          out.closeEntry()
        }
      }
      val definitions = JarFile(jar.toFile()).use { input ->
        input.entries().asSequence().filter { it.name.endsWith(".class") }.associate {
          it.name.removeSuffix(".class").replace('/', '.') to input.getInputStream(it).use { stream -> stream.readBytes() }
        }
      }
      val loader = object : ClassLoader(original.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
          val type = findLoadedClass(name) ?: definitions[name]?.let { defineClass(name, it, 0, it.size) }
            ?: super.loadClass(name, false)
          if (resolve) resolveClass(type)
          type
        }
      }
      val transformed = loader.loadClass(original.name)
      assertNotSame(original, transformed)
      assertSame(loader, transformed.classLoader)
      assertSame(original.classLoader.loadClass("kotlin.jvm.internal.Intrinsics"),
        loader.loadClass("kotlin.jvm.internal.Intrinsics"))
      val before = original.getConstructor().newInstance()
      val after = transformed.getConstructor().newInstance()
      val integer = Int::class.javaPrimitiveType!!
      for (name in listOf("calculate", "write", "invoking")) for (value in listOf(-7, 1, 23, Int.MAX_VALUE)) {
        assertEquals(original.getMethod(name, integer).invoke(before, value),
          transformed.getMethod(name, integer).invoke(after, value), name)
      }
      assertEquals(original.getMethod("read").invoke(before), transformed.getMethod("read").invoke(after))
      val value = Any()
      for (name in listOf("identity", "nullable", "references")) {
        assertSame(value, original.getMethod(name, Any::class.java).invoke(before, value))
        assertSame(value, transformed.getMethod(name, Any::class.java).invoke(after, value))
      }
      for (name in listOf("nullable", "references")) {
        assertNull(original.getMethod(name, Any::class.java).invoke(before, null))
        assertNull(transformed.getMethod(name, Any::class.java).invoke(after, null))
      }
      assertSame(before, original.getMethod("self").invoke(before))
      assertSame(after, transformed.getMethod("self").invoke(after))
      assertNull(original.getMethod("nothing").invoke(before))
      assertNull(transformed.getMethod("nothing").invoke(after))
      for ((name, parameter, argument) in listOf(
        Triple("identity", Any::class.java, null), Triple("invoking", integer, 0))) {
        val expected = assertThrows(InvocationTargetException::class.java) {
          original.getMethod(name, parameter).invoke(before, argument)
        }.cause!!
        val actual = assertThrows(InvocationTargetException::class.java) {
          transformed.getMethod(name, parameter).invoke(after, argument)
        }.cause!!
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
      }
    }
  }
}