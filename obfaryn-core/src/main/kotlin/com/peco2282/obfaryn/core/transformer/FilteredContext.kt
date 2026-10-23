package com.peco2282.obfaryn.core.transformer

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.context.ResourceEntry
import org.objectweb.asm.tree.ClassNode

/**
 * Pass-facing view hiding excluded entries from class/resource enumeration.
 *
 * Enumerated maps are filtered snapshots, but their nodes and entries still belong to [delegate].
 * Direct lookup, existence checks, and transformation policy are delegated without filtering,
 * allowing a pass to resolve excluded dependencies. Replacing an excluded key is rejected and
 * removing one returns null; other mutations write through. This is a cooperative filtering
 * boundary, not isolation from mutable excluded nodes returned by direct lookup.
 */
class FilteredContext(
  private val delegate: ObfuscationContext,
  private val excludedClasses: Set<String>,
  private val excludedResources: Set<String>,
) : ObfuscationContext by delegate {
  override val classes get() = delegate.classes.filterKeys { it !in excludedClasses }
  override val resources get() = delegate.resources.filterKeys { it !in excludedResources }
  override operator fun plusAssign(node: ClassNode) { putClass(node) }
  override operator fun plusAssign(entry: ResourceEntry) { putResource(entry) }
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