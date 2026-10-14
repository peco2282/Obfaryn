package com.peco2282.obfaryn.core.transformer

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.context.ResourceEntry
import org.objectweb.asm.tree.ClassNode

class FilteredContext(
  private val delegate: ObfuscationContext,
  private val excludedClasses: Set<String>,
  private val excludedResources: Set<String>,
) : ObfuscationContext by delegate {
  override val classes get() = delegate.classes.filterKeys { it !in excludedClasses }
  override val resources get() = delegate.resources.filterKeys { it !in excludedResources }
  override fun putClass(node: ClassNode) {
    require(node.name !in excludedClasses)
    delegate.putClass(node)
  }
  override fun removeClass(internalName: String): ClassNode? =
    if (internalName in excludedClasses) null else delegate.removeClass(internalName)
  override fun putResource(entry: ResourceEntry) {
    require(entry.name !in excludedResources)
    delegate.putResource(entry)
  }
  override fun removeResource(name: String): ResourceEntry? =
    if (name in excludedResources) null else delegate.removeResource(name)
}