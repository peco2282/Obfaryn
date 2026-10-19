package com.peco2282.obfaryn.api

import com.peco2282.obfaryn.api.context.ResourceEntry
import org.objectweb.asm.tree.ClassNode

/**
 * Represents the execution environment context provided to a [Platform].
 *
 * Provides access to all loaded classes and resources, enabling platform implementations
 * to detect target frameworks, extract entrypoints, and configure platform-specific rules.
 * Treat this as an inspection interface: contribute new content through a [ClassSink],
 * [ResourceSink], or [ArchiveSink], and add keep rules through [PlatformConfiguration].
 * Read-only maps do not make their ASM nodes or resource byte arrays immutable.
 */
interface PlatformContext {
  /**
   * A read-only map of loaded classes keyed by JVM internal name (e.g. `com/example/MyClass`).
   * Names do not include a `.class` suffix, and values are not defensive copies.
   */
  val classes: Map<String, ClassNode>

  /**
   * A read-only map of non-class resources keyed by archive-relative path (e.g. `plugin.yml`).
   * Values may contain mutable byte arrays; implementations need not provide snapshots.
   */
  val resources: Map<String, ResourceEntry>
}
