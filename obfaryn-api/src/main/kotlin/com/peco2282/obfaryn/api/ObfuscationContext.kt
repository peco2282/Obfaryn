package com.peco2282.obfaryn.api

import com.peco2282.obfaryn.api.context.ResourceEntry
import com.peco2282.obfaryn.api.transformer.TransformationPolicy
import org.objectweb.asm.tree.ClassNode

/**
 * Represents the execution context for obfuscation and transformation operations.
 *
 * Provides access to all classes and non-class resources within the target archive or project,
 * allowing transformers to inspect, insert, update, or remove bytecode definitions and resource files.
 */
interface ObfuscationContext {
  /** Consult before rewriting a method body; custom contexts may supply their own policy. */
  val transformationPolicy: TransformationPolicy
    get() = TransformationPolicy.ALLOW_ALL
  /**
   * An immutable view of all loaded classes in this context, keyed by their internal names (e.g. `com/example/MyClass`).
   */
  val classes: Map<String, ClassNode>

  /**
   * An immutable view of all loaded non-class resource entries in this context, keyed by their relative file paths.
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
   *
   * @param node The [ClassNode] to add or replace.
   */
  fun putClass(node: ClassNode)

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
   *
   * @param entry The [ResourceEntry] to add or replace.
   */
  fun putResource(entry: ResourceEntry)

  /**
   * Removes a resource with the given [name] from the context.
   *
   * @param name The relative path or identifier of the resource to remove.
   * @return The removed [ResourceEntry], or `null` if no resource with that name was present.
   */
  fun removeResource(name: String): ResourceEntry?
}
