package com.peco2282.obfaryn.core.assembly

import com.peco2282.obfaryn.api.PlatformContext
import com.peco2282.obfaryn.api.context.ResourceEntry
import org.objectweb.asm.tree.ClassNode

/**
 * A flat, read-only [PlatformContext] union over every artifact in an assembly.
 *
 * It is supplied to each artifact's platform so cross-artifact references (entrypoints, call
 * closures) resolve against the whole program during detection and configuration. Class nodes and
 * resource entries are shared with the artifacts' own contexts, not copied, so this view is a
 * reference window and must not be mutated. Its own [wholeProgramView] is `null`.
 */
class WholeProgramView(
  override val classes: Map<String, ClassNode>,
  override val resources: Map<String, ResourceEntry>,
) : PlatformContext {
  companion object {
    /**
     * Builds the union of [contexts].
     *
     * Classes keep their original nodes. A class name defined by more than one artifact is a
     * collision the caller must have rejected already; here the first occurrence in iteration order
     * wins, since the view is only an inspection surface. Resource paths are deduplicated the same
     * way (artifacts routinely share paths such as `META-INF/MANIFEST.MF`).
     */
    fun of(contexts: Collection<PlatformContext>): WholeProgramView {
      val classes = LinkedHashMap<String, ClassNode>()
      val resources = LinkedHashMap<String, ResourceEntry>()
      for (context in contexts) {
        for ((name, node) in context.classes) classes.putIfAbsent(name, node)
        for ((path, entry) in context.resources) resources.putIfAbsent(path, entry)
      }
      return WholeProgramView(classes, resources)
    }
  }
}
