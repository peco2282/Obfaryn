package com.peco2282.obfaryn.platformkit

import com.peco2282.obfaryn.api.PlatformContext
import com.peco2282.obfaryn.api.transformer.MemberKey
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

/**
 * [seeds] plus every method reachable from them by following `invoke*` instructions, restricted
 * to methods on a class accepted by [scope]. Constructors and static initializers are traversed
 * but never added as targets in their own right, matching how callers usually only care about
 * reachable *behavior*, not initialization. A seed outside [scope] or missing from this context is
 * dropped silently rather than failing the whole call.
 *
 * Typical use: start from methods identified as performance-sensitive by name/annotation, then
 * propagate that classification to whatever they call, so a hot callback's helpers are not
 * virtualized out from under it.
 */
fun PlatformContext.methodCallClosure(
  seeds: Set<MemberKey>,
  scope: (ClassNode) -> Boolean = { true },
): Set<MemberKey> {
  val methods: Map<MemberKey, MethodNode> = classes.values.asSequence()
    .filter(scope)
    .flatMap { owner -> owner.methods.asSequence().map { MemberKey(owner.name, it.name, it.desc) to it } }
    .toMap()

  val visited = linkedSetOf<MemberKey>()
  val queue = ArrayDeque<MemberKey>()
  for (seed in seeds) if (seed in methods && visited.add(seed)) queue.addLast(seed)

  while (queue.isNotEmpty()) {
    val method = methods.getValue(queue.removeFirst())
    for (instruction in method.instructions) {
      val invoke = instruction as? MethodInsnNode ?: continue
      if (invoke.name.startsWith("<")) continue
      val target = MemberKey(invoke.owner, invoke.name, invoke.desc)
      if (target in methods && visited.add(target)) queue.addLast(target)
    }
  }
  return visited
}
