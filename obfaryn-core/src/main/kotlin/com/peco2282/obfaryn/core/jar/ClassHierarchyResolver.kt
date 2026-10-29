package com.peco2282.obfaryn.core.jar

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.core.platformClassLoader
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/** Resolves hierarchy from transformed input, dependency archives/directories and JDK class resources. */
class ClassHierarchyResolver(context: ObfuscationContext, classpath: List<Path>) : AutoCloseable {
  data class Info(
    val superName: String?, val interfaces: List<String>, val access: Int,
    val methods: Map<Pair<String, String>, Int>,
  ) {
    val isInterface: Boolean get() = access and Opcodes.ACC_INTERFACE != 0
  }

  private val input = context.classes.mapValues { (_, node) ->
    Info(node.superName, node.interfaces.toList(), node.access,
      node.methods.associate { (it.name to it.desc) to it.access })
  }
  private val archives = mutableListOf<JarFile>()
  private val directories = mutableListOf<Path>()
  private val cache = mutableMapOf<String, Info?>()

  init {
    for (path in classpath) {
      require(Files.exists(path)) { "Classpath entry does not exist: $path" }
      if (Files.isDirectory(path)) directories.add(path)
      else archives.add(JarFile(path.toFile()))
    }
  }

  fun lookup(name: String): Info? {
    input[name]?.let { return it }
    if (name in cache) return cache[name]
    val bytes = sequence {
      for (directory in directories) {
        val file = directory.resolve("$name.class")
        if (Files.isRegularFile(file)) yield(Files.readAllBytes(file))
      }
      for (archive in archives) {
        val entry = archive.getJarEntry("$name.class") ?: continue
        yield(archive.getInputStream(entry).use { it.readBytes() })
      }
      val resource = "$name.class"
      platformClassLoader()
        .getResourceAsStream(resource)
        .use { yield(it?.readBytes()) }
//      ClassLoader.getPlatformClassLoader().getResourceAsStream(resource)?.use { yield(it.readBytes()) }
    }.firstOrNull() ?: run { cache[name] = null; return null }
    val reader = ClassReader(bytes)
    val node = ClassNode()
    reader.accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    val result = Info(node.superName, node.interfaces.toList(), node.access,
      node.methods.associate { (it.name to it.desc) to it.access })
    cache[name] = result
    return result
  }

  private fun info(name: String): Info = lookup(name)
    ?: throw IllegalStateException("Cannot resolve class hierarchy for $name; add its dependency with --classpath")

  private fun parents(name: String): List<String> {
    if (name.startsWith("[")) return listOf("java/lang/Object", "java/lang/Cloneable", "java/io/Serializable")
    val current = info(name)
    return listOfNotNull(current.superName) + current.interfaces
  }

  private fun assignable(target: String, source: String, seen: MutableSet<String> = mutableSetOf()): Boolean {
    if (target == source || target == "java/lang/Object") return true
    if (!seen.add(source)) return false
    if (source.startsWith("[")) {
      if (target in setOf("java/lang/Cloneable", "java/io/Serializable")) return true
      if (!target.startsWith("[")) return false
      val targetComponent = target.drop(1)
      val sourceComponent = source.drop(1)
      if (!targetComponent.startsWith("L") && !targetComponent.startsWith("[")) return target == source
      if (!sourceComponent.startsWith("L") && !sourceComponent.startsWith("[")) return false
      return assignable(targetComponent.removeSurrounding("L", ";"), sourceComponent.removeSurrounding("L", ";"))
    }
    if (target.startsWith("[")) return false
    return parents(source).any { assignable(target, it, seen) }
  }

  fun commonSuperClass(a: String, b: String): String {
    if (assignable(a, b)) return a
    if (assignable(b, a)) return b
    if (a.startsWith("[") || b.startsWith("[") || info(a).isInterface || info(b).isInterface) return "java/lang/Object"
    var cursor: String? = a
    while (cursor != null) {
      if (assignable(cursor, b)) return cursor
      cursor = info(cursor).superName
    }
    return "java/lang/Object"
  }

  override fun close() { archives.forEach(JarFile::close) }
}
