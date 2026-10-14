package com.peco2282.obfaryn.api

import com.peco2282.obfaryn.api.context.ResourceEntry
import org.objectweb.asm.tree.ClassNode

/**
 * Represents the execution environment context provided to a [Platform].
 *
 * Provides access to all loaded classes and resources, enabling platform implementations
 * to detect target frameworks, extract entrypoints, and configure platform-specific rules.
 */
interface PlatformContext {
  /**
   * An immutable view of all loaded classes in this context, keyed by their internal names (e.g. `com/example/MyClass`).
   */
  val classes: Map<String, ClassNode>

  /**
   * An immutable view of all loaded non-class resource entries in this context, keyed by their relative file paths.
   */
  val resources: Map<String, ResourceEntry>
}
