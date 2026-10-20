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
 *
 * Inspect names using their archive/JVM spelling rather than filesystem paths:
 * ```kotlin
 * fun hasExampleEntrypoint(context: PlatformContext): Boolean {
 *   val main = context.classes["com/example/Main"] ?: return false
 *   return "example-plugin.yml" in context.resources &&
 *     main.methods.any { it.name == "onStart" && it.desc == "()V" }
 * }
 * ```
 * A missing key is normal and should be handled by detection or discovery logic. The interface
 * does not promise that dependency classes are present, nor that the maps are safe to mutate
 * concurrently. Do not retain mutable values for modification after a platform callback.
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
