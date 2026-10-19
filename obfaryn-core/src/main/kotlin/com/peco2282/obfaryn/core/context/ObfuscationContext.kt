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
 * Additions and removals go through the interface methods to keep lookup keys in sync.
 */
class ObfuscationContext(
  classes: MutableMap<String, ClassNode>,
  resources: MutableMap<String, ResourceEntry>,
) : ObfuscationContextApi, PlatformContext {
  private val classMap = LinkedHashMap(classes)
  private val resourceMap = LinkedHashMap(resources)
  override val classes: Map<String, ClassNode> = Collections.unmodifiableMap(classMap)
  override val resources: Map<String, ResourceEntry> = Collections.unmodifiableMap(resourceMap)
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
