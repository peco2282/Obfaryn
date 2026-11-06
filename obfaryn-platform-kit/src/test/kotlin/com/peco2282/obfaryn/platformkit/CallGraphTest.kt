package com.peco2282.obfaryn.platformkit

import com.peco2282.obfaryn.api.PlatformContext
import com.peco2282.obfaryn.api.context.ResourceEntry
import com.peco2282.obfaryn.api.transformer.MemberKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

private class FakePlatformContext(override val classes: Map<String, ClassNode>) : PlatformContext {
  override val resources: Map<String, ResourceEntry> = emptyMap()
}

private fun method(name: String, calls: List<MemberKey> = emptyList()) =
  MethodNode(Opcodes.ACC_PUBLIC, name, "()V", null, null).apply {
    for (call in calls) instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, call.owner, call.name, call.desc))
  }

private fun classNode(name: String, vararg methods: MethodNode) = ClassNode().apply {
  this.name = name
  this.version = Opcodes.V1_8
  this.superName = "java/lang/Object"
  this.methods.addAll(methods)
}

class CallGraphTest {
  private val helper = MemberKey("com/example/B", "helper", "()V")
  private val util = MemberKey("com/example/C", "util", "()V")
  private val unreachable = MemberKey("com/example/D", "unreachable", "()V")

  private val context = FakePlatformContext(
    listOf(
      classNode("com/example/A", method("seed", listOf(helper))),
      classNode("com/example/B", method("helper", listOf(util))),
      classNode("com/example/C", method("util")),
      classNode("com/example/D", method("unreachable")),
    ).associateBy { it.name },
  )

  @Test
  fun `includes the seed and every transitive callee`() {
    val seed = MemberKey("com/example/A", "seed", "()V")
    val closure = context.methodCallClosure(setOf(seed))
    assertEquals(setOf(seed, helper, util), closure)
  }

  @Test
  fun `never pulls in a method nothing in the seed set reaches`() {
    val seed = MemberKey("com/example/A", "seed", "()V")
    val closure = context.methodCallClosure(setOf(seed))
    assertFalse(unreachable in closure)
  }

  @Test
  fun `a scope that excludes a class drops it from the closure, even if called`() {
    val seed = MemberKey("com/example/A", "seed", "()V")
    val closure = context.methodCallClosure(setOf(seed)) { it.name != "com/example/C" }
    assertEquals(setOf(seed, helper), closure)
  }

  @Test
  fun `a seed outside scope contributes nothing`() {
    val seed = MemberKey("com/example/A", "seed", "()V")
    val closure = context.methodCallClosure(setOf(seed)) { it.name != "com/example/A" }
    assertEquals(emptySet<MemberKey>(), closure)
  }
}
