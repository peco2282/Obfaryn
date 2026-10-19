package com.peco2282.obfaryn.api

import com.peco2282.obfaryn.api.context.ResourceEntry
import com.peco2282.obfaryn.api.transformer.TransformationPolicy
import org.objectweb.asm.tree.ClassNode

/**
 * Represents the execution context for obfuscation and transformation operations.
 *
 * Provides access to all classes and non-class resources within the target archive or project,
 * allowing transformers to inspect, insert, update, or remove bytecode definitions and resource files.
 * Class names use JVM internal form (`com/example/MyClass`, without `.class`); resource names
 * use archive-relative paths (`META-INF/services/com.example.Service`).
 *
 * The maps are read-only interfaces, not deep immutable snapshots. Their [ClassNode] values and
 * resource byte arrays remain mutable. Use the mutation methods to add, replace, or remove entries,
 * and consult [transformationPolicy] before modifying method bodies.
 */
interface ObfuscationContext {
  /**
   * Permission check for method-body changes, including changes made by custom transformers.
   *
   * Defaults to [TransformationPolicy.ALLOW_ALL]. The context does not automatically intercept
   * mutations of ASM nodes; each transformer is responsible for consulting this policy.
   */
  val transformationPolicy: TransformationPolicy
    get() = TransformationPolicy.ALLOW_ALL
  /**
   * A read-only map of loaded classes keyed by internal name (e.g. `com/example/MyClass`).
   * The nodes are mutable; this interface does not guarantee a snapshot of the map.
   */
  val classes: Map<String, ClassNode>

  /**
   * A read-only map of non-class resources keyed by archive-relative path.
   * Resource byte arrays are mutable; this interface does not guarantee a snapshot of the map.
   */
  val resources: Map<String, ResourceEntry>

  /**
   * Retrieves the [ClassNode] corresponding to the given class [internalName].
   *
   * @param internalName The internal name of the class (e.g. `com/example/MyClass`).
   * @return The [ClassNode] if found, or `null` if no class exists with that name.
   */
  fun findClass(internalName: String): ClassNode?

  /**
   * Checks whether a class with the given [internalName] exists in the context.
   *
   * @param internalName The internal name of the class (e.g. `com/example/MyClass`).
   * @return `true` if the class is present in the context, `false` otherwise.
   */
  fun hasClass(internalName: String): Boolean

  /**
   * Retrieves the [ResourceEntry] corresponding to the given resource [name].
   *
   * @param name The relative path or identifier of the resource.
   * @return The [ResourceEntry] if found, or `null` if no resource exists with that name.
   */
  fun getResource(name: String): ResourceEntry?

  /**
   * Checks whether a resource with the given [name] exists in the context.
   *
   * @param name The relative path or identifier of the resource.
   * @return `true` if the resource is present in the context, `false` otherwise.
   */
  fun hasResource(name: String): Boolean

  /**
   * Adds or updates a class in the context.
   *
   * The class is stored using its [ClassNode.name] as the lookup key.
   * An existing entry at that key is replaced. Changing a node's name does not itself remove an
   * entry under its old key; use [removeClass] when reindexing a renamed class.
   *
   * @param node The [ClassNode] to add or replace.
   */
  fun putClass(node: ClassNode)
  operator fun plusAssign(node: ClassNode) = putClass(node)

  /**
   * Removes a class with the given [internalName] from the context.
   *
   * @param internalName The internal name of the class to remove.
   * @return The removed [ClassNode], or `null` if no class with that name was present.
   */
  fun removeClass(internalName: String): ClassNode?

  /**
   * Adds or updates a non-class resource in the context.
   *
   * The resource is stored using its [ResourceEntry.name] as the lookup key.
   * An existing entry at that key is replaced. Use [removeResource] to remove a previous path
   * when moving a resource to a different name.
   *
   * @param entry The [ResourceEntry] to add or replace.
   */
  fun putResource(entry: ResourceEntry)
  operator fun plusAssign(entry: ResourceEntry) = putResource(entry)

  /**
   * Removes a resource with the given [name] from the context.
   *
   * @param name The relative path or identifier of the resource to remove.
   * @return The removed [ResourceEntry], or `null` if no resource with that name was present.
   */
  fun removeResource(name: String): ResourceEntry?
}
