package com.peco2282.obfaryn.platformkit

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

private fun classNode(name: String, superName: String? = "java/lang/Object", interfaces: List<String> = emptyList()) =
  ClassNode().apply {
    this.name = name
    this.superName = superName
    this.interfaces = interfaces
    version = Opcodes.V1_8
  }

class SubtypingTest {
  private val classes = listOf(
    classNode("com/example/Base"),
    classNode("com/example/Middle", superName = "com/example/Base", interfaces = listOf("com/example/Marker")),
    classNode("com/example/Marker", superName = null, interfaces = emptyList()),
    classNode("com/example/Leaf", superName = "com/example/Middle"),
  ).associateBy { it.name }

  @Test
  fun `walks superclasses transitively`() {
    assertTrue(classes.isSubtypeOf("com/example/Leaf", "com/example/Base"))
  }

  @Test
  fun `walks interfaces transitively`() {
    assertTrue(classes.isSubtypeOf("com/example/Leaf", "com/example/Marker"))
  }

  @Test
  fun `accepts dotted names on either side`() {
    assertTrue(classes.isSubtypeOf("com.example.Leaf", "com.example.Base"))
  }

  @Test
  fun `a type is its own subtype`() {
    assertTrue(classes.isSubtypeOf("com/example/Base", "com/example/Base"))
  }

  @Test
  fun `does not walk upward from an unrelated type`() {
    assertFalse(classes.isSubtypeOf("com/example/Base", "com/example/Leaf"))
  }

  @Test
  fun `a class missing from the map ends that branch instead of throwing`() {
    assertFalse(classes.isSubtypeOf("com/example/Leaf", "com/example/NotLoaded"))
  }

  @Test
  fun `matches by Class overload`() {
    val runnableImpl = classNode("com/example/RunnableImpl", interfaces = listOf("java/lang/Runnable"))
    assertTrue((classes + (runnableImpl.name to runnableImpl)).isSubtypeOf(runnableImpl.name, Runnable::class.java))
  }

  @Test
  fun `matches by reified type parameter`() {
    val runnableImpl = classNode("com/example/RunnableImpl", interfaces = listOf("java/lang/Runnable"))
    assertTrue((classes + (runnableImpl.name to runnableImpl)).isSubtypeOf<Runnable>(runnableImpl.name))
  }
}
