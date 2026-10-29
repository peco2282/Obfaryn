package com.peco2282.obfaryn.core.fixtures

import org.junit.jupiter.api.Assertions.assertEquals
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.*
import javax.tools.ToolProvider

/** Shared compiler/JAR fixture; individual feature tests own their behavior assertions. */
object JavaJarFixture {
  fun compile(dir: Path, sources: Map<String, String>): Path {
    val files = sources.map { (name, source) ->
      dir.resolve("sources/$name.java").also { Files.createDirectories(it.parent); Files.write(it, source.toByteArray()) }
    }
    val classes = dir.resolve("classes"); Files.createDirectories(classes)
    val annotations = com.peco2282.obfaryn.annotations.Protection::class.java.protectionDomain.codeSource.location.toURI()
    val kotlin = kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI()
    val args = listOf("--release", "8", "-classpath", listOf(annotations, kotlin).joinToString(java.io.File.pathSeparator) {
      java.nio.file.Paths.get(it).toString()
    }, "-d", classes.toString()) + files.map(Path::toString)
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *args.toTypedArray()))
    val input = dir.resolve("input.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      Files.walk(classes).use { paths -> paths.filter { it.toString().endsWith(".class") }.sorted().forEach {
        jar.putNextEntry(JarEntry(classes.relativize(it).toString().replace('\\', '/')))
        jar.write(Files.readAllBytes(it)); jar.closeEntry()
      } }
    }
    return input
  }

  fun classes(jar: Path): List<ClassNode> = JarFile(jar.toFile()).use { file ->
    file.entries().asSequence().filter { it.name.endsWith(".class") }.map { entry ->
      ClassNode().also { node -> file.getInputStream(entry).use { ClassReader(it).accept(node, 0) } }
    }.toList()
  }
}
