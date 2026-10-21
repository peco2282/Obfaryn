package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.rename.KotlinMetadataRemapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

class KotlinMetadataCompatibilityTest {
  private fun sample() = ClassNode().also { node ->
    javaClass.classLoader.getResourceAsStream("fixture/KotlinData.class")!!.use { ClassReader(it).accept(node, 0) }
  }

  @Test fun syntheticMetadataHeadersCoverOldAndCurrentReaderVersions() {
    for (version in listOf(listOf(1, 1, 16), listOf(1, 4, 0), listOf(1, 9, 0),
      listOf(2, 0, 0), listOf(2, 1, 0), listOf(2, 3, 0), listOf(2, 4, 0))) {
      val node = sample()
      val annotation = node.visibleAnnotations.single { it.desc == "Lkotlin/Metadata;" }
      annotation.values[annotation.values.indexOf("mv") + 1] = version
      val metadata = KotlinMetadataRemapper.prepare(listOf(node), emptyMap())
      metadata.rewrite(node, mapOf(node.name to "flat/Versioned"))
      val updated = node.visibleAnnotations.single { it.desc == "Lkotlin/Metadata;" }
      val written = updated.values[updated.values.indexOf("mv") + 1] as List<*>
      assertTrue((written[0] as Int) > 1 || (written[1] as Int) >= 4, version.toString())
    }
  }

  @Test fun unsupportedVersionIsRejectedBeforeChangingClasses() {
    val node = sample()
    val annotation = node.visibleAnnotations.single { it.desc == "Lkotlin/Metadata;" }
    annotation.values[annotation.values.indexOf("mv") + 1] = listOf(99, 0, 0)
    val error = assertThrows(IllegalArgumentException::class.java) {
      KotlinMetadataRemapper.prepare(listOf(node), emptyMap())
    }
    assertTrue(error.message!!.contains(node.name))
    assertEquals("fixture/KotlinData", node.name)
  }

  @Test fun malformedModuleIsRejectedBeforeChangingClasses() {
    val node = sample()
    assertThrows(IllegalArgumentException::class.java) {
      KotlinMetadataRemapper.prepare(listOf(node), mapOf("META-INF/broken.kotlin_module" to byteArrayOf(0, 1, 2)))
    }
    assertEquals("fixture/KotlinData", node.name)
  }
}