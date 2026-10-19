package com.peco2282.obfaryn.core.context


import com.peco2282.obfaryn.api.PlatformContext
import com.peco2282.obfaryn.api.context.ResourceEntry
import com.peco2282.obfaryn.api.transformer.TransformationPolicy
import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy
import org.objectweb.asm.tree.ClassNode
import java.util.*
import com.peco2282.obfaryn.api.ObfuscationContext as ObfuscationContextApi

/**
 * Mutable archive state shared by platform detection and transformation passes.
 *
 * [classes] and [resources] expose unmodifiable map views; contained ASM nodes remain mutable.
 * Constructor maps are shallow-copied into insertion-ordered maps: later changes to the supplied
 * maps are not reflected, but nodes and resource entries are shared. The exposed views reflect
 * subsequent additions and removals through this context.
 *
 * Classes are keyed by JVM internal name and resources by archive path. Put operations replace
 * entries with the same key. Changing a node's name directly does not reindex it: remove the old
 * entry and put the renamed node. This mutable context provides no synchronization.
 */
class ObfuscationContext(
  classes: MutableMap<String, ClassNode>,
  resources: MutableMap<String, ResourceEntry>,
) : ObfuscationContextApi, PlatformContext {
  private val classMap = LinkedHashMap(classes)
  private val resourceMap = LinkedHashMap(resources)
  override val classes: Map<String, ClassNode> = Collections.unmodifiableMap(classMap)
  override val resources: Map<String, ResourceEntry> = Collections.unmodifiableMap(resourceMap)
  /** Evaluates the current annotations on each query; it is not a cached eligibility result. */
  override val transformationPolicy = TransformationPolicy { owner, method ->
    !ObfuscationAnnotationPolicy.skipBody(owner, method)
  }

  override fun findClass(internalName: String): ClassNode? = classes[internalName]

  override fun hasClass(internalName: String): Boolean = internalName in classes

  override fun getResource(name: String): ResourceEntry? = resources[name]

  override fun hasResource(name: String): Boolean = name in resources
  override fun putClass(node: ClassNode) { classMap[node.name] = node }
  override fun removeClass(internalName: String): ClassNode? = classMap.remove(internalName)
  override fun putResource(entry: ResourceEntry) { resourceMap[entry.name] = entry }
  override fun removeResource(name: String): ResourceEntry? = resourceMap.remove(name)
}
