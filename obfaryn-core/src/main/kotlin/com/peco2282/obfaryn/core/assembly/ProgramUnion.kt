package com.peco2282.obfaryn.core.assembly

import com.peco2282.obfaryn.core.context.ObfuscationContext
import org.objectweb.asm.tree.ClassNode

/**
 * The single naming universe a cross-artifact rename operates on, plus the ledger that routes each
 * renamed class back to the artifact it came from.
 *
 * [context] presents every artifact's classes as one [ObfuscationContext] sharing the original
 * [ClassNode] instances (never copies), so the renamer sees one program and rewrites references
 * across artifact boundaries. It carries no resources: resource paths collide between artifacts and
 * are kept local to each one. [origins] maps every original internal class name to the id of the
 * artifact that defined it, and is the sole basis for splitting the renamed result back out.
 *
 * `module-info` is excluded from the union: every artifact's module descriptor shares the internal
 * name `module-info`, so it cannot live in one naming universe. It stays with each artifact and is
 * remapped locally when the result is written.
 */
class ProgramUnion private constructor(
  val context: ObfuscationContext,
  val origins: Map<String, String>,
) {
  companion object {
    const val MODULE_INFO = "module-info"

    /**
     * Builds the union of [modules] (artifact id to that artifact's post-body context).
     *
     * @throws IllegalArgumentException if two artifacts define a class with the same internal name.
     *   Body passes can generate helper classes, so this also rejects cross-artifact helper-name
     *   collisions; give colliding artifacts disjoint helper namespaces to avoid it.
     */
    fun build(modules: List<Pair<String, ObfuscationContext>>): ProgramUnion {
      val classes = LinkedHashMap<String, ClassNode>()
      val origins = LinkedHashMap<String, String>()
      for ((id, module) in modules) {
        for ((name, node) in module.classes) {
          if (name == MODULE_INFO) continue
          val previous = origins.putIfAbsent(name, id)
          require(previous == null) {
            "Class $name is defined by both artifact '$previous' and artifact '$id'; " +
              "cross-artifact assembly requires a single naming universe with no duplicate classes"
          }
          classes[name] = node
        }
      }
      val union = ObfuscationContext(classes, LinkedHashMap())
      // Carry over any original-name tracking the artifacts' body passes recorded, so the renamer's
      // identity resolution and the post-rename split both see pre-assembly identities.
      for ((_, module) in modules) {
        for ((mapped, original) in module.originalClassNames) if (mapped != MODULE_INFO) union.originalClassNames[mapped] = original
        union.originalMethodIdentities.putAll(module.originalMethodIdentities)
      }
      return ProgramUnion(union, origins)
    }
  }
}
