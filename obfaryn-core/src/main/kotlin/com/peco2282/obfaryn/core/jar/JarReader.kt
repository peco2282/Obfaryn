package com.peco2282.obfaryn.core.jar

import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.api.context.ResourceEntry
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

import java.nio.file.Path
import java.util.jar.JarFile


/**
 * Loads a JAR into ASM class nodes and untouched resource bytes.
 *
 * Multi-release class entries remain resources at their original paths; Obfuscator checks
 * whether those bytes can safely be preserved when application classes are renamed.
 */
class JarReader {
  /**
   * Reads non-directory entries in path order, expanding ASM frames for base class entries.
   * Class keys come from the bytecode's internal name, not the ZIP entry path; two entries
   * defining the same class are rejected. Resource bytes and paths, including manifests and
   * signatures, are retained without interpretation. The archive is closed before returning.
   *
   * @throws IllegalArgumentException If duplicate class definitions are encountered.
   */
  fun read(path: Path): ObfuscationContext {
    val classes = linkedMapOf<String, ClassNode>()
    val resources = linkedMapOf<String, ResourceEntry>()

    JarFile(path.toFile()).use { jar ->
      val entries = jar.entries().asSequence().filterNot { it.isDirectory }.sortedBy { it.name }.toList()

      for (entry in entries) {

        val data = jar.getInputStream(entry).use { input ->
          input.readBytes()
        }

        // Versioned definitions require separate handling; keep their exact path and bytes.
        // Obfuscator rejects a versioned class that could refer to renamed application code.
        if (entry.name.endsWith(".class") && !entry.name.startsWith("META-INF/versions/")) {
          val reader = ClassReader(data)

          val node = ClassNode()

          reader.accept(
            node,
            ClassReader.EXPAND_FRAMES,
          )

          require(classes.putIfAbsent(node.name, node) == null) { "Duplicate class: ${node.name}" }
        } else {
          resources[entry.name] = ResourceEntry(
            name = entry.name,
            data = data,
          )
        }
      }
    }

    return ObfuscationContext(
      classes = classes,
      resources = resources,
    )
  }
}
