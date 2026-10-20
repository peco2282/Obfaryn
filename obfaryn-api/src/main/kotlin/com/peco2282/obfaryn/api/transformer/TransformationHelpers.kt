package com.peco2282.obfaryn.api.transformer

import com.peco2282.obfaryn.api.ObfuscationContext
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

/**
 * Visits a snapshot of classes and methods allowed by the host's body policy.
 * Newly added methods are not visited. This checks permission, not bytecode eligibility;
 * callers must still check constructors, abstract/native methods and supported instructions.
 */
fun ObfuscationContext.forEachTransformableMethod(action: (ClassNode, MethodNode) -> Unit) {
  val methods = classes.values.toList().flatMap { owner -> owner.methods.toList().map { owner to it } }
  for ((owner, method) in methods) {
    if (transformationPolicy.canTransformBody(owner, method)) action(owner, method)
  }
}

/** Finds an unused method name, conservatively avoiding every existing overload. */
fun ClassNode.uniqueMethodName(prefix: String): String {
  require(prefix.isNotBlank() && prefix.none { it in ".;/[<>" }) { "Invalid method name prefix: $prefix" }
  val names = methods.mapTo(hashSetOf()) { it.name }
  var candidate = prefix
  var suffix = 0
  while (candidate in names) candidate = "$prefix$${++suffix}"
  return candidate
}
