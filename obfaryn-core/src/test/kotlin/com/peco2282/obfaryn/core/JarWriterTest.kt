package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.context.ResourceEntry
import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.jar.JarWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.nio.file.Path
import java.util.jar.JarFile

class JarWriterTest {
  @TempDir lateinit var dir: Path

  @Test fun writesParentDirectoriesForClassLoaderPackageDiscovery() {
    val node = ClassNode().apply {
      version = Opcodes.V1_8
      access = Opcodes.ACC_PUBLIC
      name = "sample/deep/Example"
      superName = "java/lang/Object"
    }
    val context = ObfuscationContext(
      linkedMapOf(node.name to node),
      linkedMapOf("META-INF/services/example.Service" to
        ResourceEntry("META-INF/services/example.Service", byteArrayOf())),
    )
    val output = dir.resolve("output.jar")
    JarWriter().write(context, output)

    JarFile(output.toFile()).use { jar ->
      val entries = jar.entries().toList()
      assertEquals(entries.map { it.name }.distinct().size, entries.size)
      for (name in listOf("sample/", "sample/deep/", "META-INF/", "META-INF/services/")) {
        assertTrue(jar.getJarEntry(name)?.isDirectory == true, name)
      }
    }
  }
}
