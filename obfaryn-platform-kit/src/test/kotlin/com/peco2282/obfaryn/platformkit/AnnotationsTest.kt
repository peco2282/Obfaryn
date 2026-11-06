package com.peco2282.obfaryn.platformkit

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.MethodNode

class AnnotationsTest {
  private fun methodWith(visible: List<String> = emptyList(), invisible: List<String> = emptyList()) =
    MethodNode().apply {
      visibleAnnotations = visible.map { AnnotationNode(it) }
      invisibleAnnotations = invisible.map { AnnotationNode(it) }
    }

  @Test
  fun `matches a runtime-visible annotation by dotted name`() {
    val method = methodWith(visible = listOf("Lorg/bukkit/event/EventHandler;"))
    assertTrue(method.isAnnotatedWith("org.bukkit.event.EventHandler"))
  }

  @Test
  fun `matches a class-retention annotation by internal name`() {
    val method = methodWith(invisible = listOf("Lcom/example/Internal;"))
    assertTrue(method.isAnnotatedWith("com/example/Internal"))
  }

  @Test
  fun `does not match an absent annotation`() {
    val method = methodWith(visible = listOf("Lorg/bukkit/event/EventHandler;"))
    assertFalse(method.isAnnotatedWith("org.bukkit.Other"))
  }

  @Test
  fun `handles a method with no annotations at all`() {
    assertFalse(MethodNode().isAnnotatedWith("org.bukkit.event.EventHandler"))
  }

  @Test
  fun `matches by Class overload`() {
    val method = methodWith(visible = listOf("Lcom/peco2282/obfaryn/platformkit/AnnotationsTest\$Marker;"))
    assertTrue(method.isAnnotatedWith(Marker::class.java))
  }

  @Test
  fun `matches by reified type parameter`() {
    val method = methodWith(visible = listOf("Lcom/peco2282/obfaryn/platformkit/AnnotationsTest\$Marker;"))
    assertTrue(method.isAnnotatedWith<Marker>())
  }

  @Retention(AnnotationRetention.RUNTIME)
  annotation class Marker
}
