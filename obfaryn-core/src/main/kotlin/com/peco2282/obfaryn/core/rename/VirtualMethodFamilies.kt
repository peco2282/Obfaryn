package com.peco2282.obfaryn.core.rename

import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy
import com.peco2282.obfaryn.core.jar.ClassHierarchyResolver
import com.peco2282.obfaryn.core.keep.KeepRules
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

/** Plans one name per override family. Missing external hierarchy is treated as a Keep. */
internal class VirtualMethodFamilies(
  private val nodes: List<ClassNode>,
  private val hierarchy: ClassHierarchyResolver,
  private val keep: KeepRules,
  private val classMapping: Map<String, String>,
) {
  private data class Signature(val name: String, val desc: String)
  private data class Declaration(val node: ClassNode, val method: MethodNode)
  private val internalNames = nodes.mapTo(hashSetOf()) { it.name }
  private val ancestryCache = mutableMapOf<String, Set<String>>()
  private val reservedNames = nodes.flatMapTo(hashSetOf()) { node -> node.methods.flatMap { listOf(it.name, it.name.removeSuffix("\$default")) } }
  private val generator = SequentialNameGenerator()

  val declarations = linkedMapOf<MemberKey, String>()
  val inheritedReferences = linkedMapOf<MemberKey, String>()
  private val dynamicNames = mutableMapOf<Pair<String, String>, MutableSet<String>>()

  fun plan() {
    val candidates = nodes.flatMap { node -> node.methods.asSequence()
      .filter(::virtual).filterNot { protected(node, it) }
      .map { Signature(it.name, it.desc) }.toList() }.toSet()
    val bySignature = nodes.flatMap { node -> node.methods.asSequence().filter(::virtual)
      .map { Signature(it.name, it.desc) to Declaration(node, it) }.toList() }
      .filter { it.first in candidates }.groupBy({ it.first }, { it.second })

    for ((sig, members) in bySignature.toSortedMap(compareBy<Signature> { it.name }.thenBy { it.desc })) {
      val byOwner = members.withIndex().associate { it.value.node.name to it.index }
      val parent = IntArray(members.size) { it }
      fun root(index: Int): Int {
        var current = index
        while (parent[current] != current) {
          parent[current] = parent[parent[current]]
          current = parent[current]
        }
        return current
      }
      fun join(a: Int, b: Int) { parent[root(b)] = root(a) }

      val touches = mutableListOf<Pair<String, List<Int>>>()
      for (node in nodes) {
        val related = ancestors(node.name).mapNotNull { ancestor ->
          val index = byOwner[ancestor] ?: return@mapNotNull null
          if (visible(members[index].method.access, ancestor, node.name)) index else null
        }
        if (related.isEmpty()) continue
        related.drop(1).forEach { join(related.first(), it) }
        touches += node.name to related
      }

      val blocked = mutableSetOf<Int>()
      for ((index, member) in members.withIndex()) if (protected(member.node, member.method)) blocked += root(index)
      for ((owner, related) in touches) {
        if (externalContract(owner, sig)) related.forEach { blocked += root(it) }
      }
      val groups = members.indices.groupBy(::root).toSortedMap()
      for ((group, indexes) in groups) {
        if (group in blocked) continue
        val owners = touches.filter { (_, related) -> related.any { root(it) == group } }.map { it.first }
        val candidate = generateName(owners, sig)
        for (index in indexes) {
          val member = members[index]
          declarations[MemberKey(member.node.name, sig.name, sig.desc)] = candidate
          dynamicNames.getOrPut(member.node.name to sig.name) { mutableSetOf() }.add(candidate)
        }
        for (owner in owners) {
          inheritedReferences[MemberKey(owner, sig.name, sig.desc)] = candidate
          dynamicNames.getOrPut(owner to sig.name) { mutableSetOf() }.add(candidate)
        }
      }
    }
  }

  fun dynamicName(owner: String, name: String): String? = dynamicNames[owner to name]?.singleOrNull()

  private fun generateName(owners: List<String>, sig: Signature): String {
    while (true) {
      val candidate = generator.next()
      if (candidate in reservedNames) continue
      if (owners.any { owner -> ancestors(owner).any { ancestor ->
          if (ancestor in internalNames) false else hierarchy.lookup(ancestor)?.methods?.get(candidate to sig.desc)
            ?.let { virtual(it) && visible(it, ancestor, owner) } == true
        } }) continue
      reservedNames += candidate
      return candidate
    }
  }

  private fun externalContract(owner: String, sig: Signature): Boolean = ancestors(owner).any { ancestor ->
    if (ancestor in internalNames) return@any false
    val external = hierarchy.lookup(ancestor) ?: return@any true
    val access = external.methods[sig.name to sig.desc] ?: return@any false
    virtual(access) && visible(access, ancestor, owner)
  }

  private fun ancestors(name: String): Set<String> = ancestryCache.getOrPut(name) {
    val result = linkedSetOf<String>()
    fun visit(current: String) {
      if (!result.add(current)) return
      val info = hierarchy.lookup(current) ?: return
      info.superName?.let(::visit)
      info.interfaces.forEach(::visit)
    }
    visit(name)
    result
  }

  private fun visible(access: Int, declaringOwner: String, descendantOwner: String): Boolean =
    declaringOwner == descendantOwner || access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0 ||
      declaringOwner.substringBeforeLast('/', "") == descendantOwner.substringBeforeLast('/', "")

  private fun virtual(method: MethodNode) = !method.name.startsWith('<') && virtual(method.access)
  private fun virtual(access: Int) = access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC) == 0

  private fun protected(node: ClassNode, method: MethodNode): Boolean =
    method.access and Opcodes.ACC_NATIVE != 0 || keep.method(node.name, method.name, method.desc) ||
      ObfuscationAnnotationPolicy.keepName(method) ||
      // Enum identity is tied to its class/constant names, not to unrelated virtual getters.
      // Keeping every enum method would also freeze its internal interface's entire family.
      node.access and (Opcodes.ACC_ANNOTATION or Opcodes.ACC_RECORD) != 0 ||
      (node.interfaces.any { it == "java/io/Serializable" || it == "java/io/Externalizable" } &&
        method.name in setOf("readResolve", "writeReplace")) ||
      (keep.publicMembers(node.name) && method.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0) ||
      objectMethod(method.name, method.desc)

  private fun objectMethod(name: String, desc: String) = when (name to desc) {
    "toString" to "()Ljava/lang/String;", "hashCode" to "()I", "equals" to "(Ljava/lang/Object;)Z",
    "clone" to "()Ljava/lang/Object;", "finalize" to "()V" -> true
    else -> false
  }
}
