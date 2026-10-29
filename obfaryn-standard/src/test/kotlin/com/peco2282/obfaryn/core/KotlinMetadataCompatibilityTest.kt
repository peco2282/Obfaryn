package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.rename.KotlinMetadataRemapper
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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

  private fun moduleWithTables(vararg extra: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
    DataOutputStream(bytes).apply {
      writeInt(3)
      writeInt(1)
      writeInt(9)
      writeInt(0)
      writeInt(0)
      // StringTable: repeated string field 1, containing annotation-related names.
      write(byteArrayOf(0x22, 0x0e, 0x0a, 0x02, 0x6a, 0x73,
        0x0a, 0x06, 0x4a, 0x73, 0x4e, 0x61, 0x6d, 0x65, 0x0a, 0x00))
      // QualifiedNameTable: one CLASS qualified name with short_name = 0.
      write(byteArrayOf(0x2a, 0x06, 0x0a, 0x04, 0x10, 0x00, 0x18, 0x00))
      extra.forEach { writeByte(it) }
    }
  }.toByteArray()

  @Test fun unusedProtobufNameTablesDoNotPreventModuleRewriting() {
    val path = "META-INF/tables.kotlin_module"
    val remapper = KotlinMetadataRemapper.prepare(emptyList(), mapOf(path to moduleWithTables()))
    val rewritten = remapper.rewriteModules(emptyMap()).getValue(path)
    KotlinMetadataRemapper.prepare(emptyList(), mapOf(path to rewritten))
  }

  @OptIn(kotlin.metadata.jvm.UnstableMetadataApi::class)
  @Test fun optionalAnnotationClassesSurviveModuleRewriting() {
    val path = "META-INF/optional.kotlin_module"
    // Class.fq_name = 0, referring to the qualified name in moduleWithTables.
    val bytes = moduleWithTables(0x82, 0x01, 0x02, 0x18, 0x00)
    val remapper = KotlinMetadataRemapper.prepare(emptyList(), mapOf(path to bytes))
    val rewritten = remapper.rewriteModules(mapOf("other/Class" to "renamed/Class")).getValue(path)
    val annotations = kotlin.metadata.jvm.KotlinModuleMetadata.read(rewritten).kmModule.optionalAnnotationClasses
    assertEquals(listOf("js"), annotations.map { it.name })
    KotlinMetadataRemapper.prepare(emptyList(), mapOf(path to rewritten))
    val error = assertThrows(IllegalArgumentException::class.java) {
      remapper.rewriteModules(mapOf("js" to "renamed/Annotation"))
    }
    assertTrue(error.message!!.contains("js -> renamed/Annotation"))
  }

  @Test fun moduleAnnotationsAreRejectedEvenWithNameTables() {
    val error = assertThrows(IllegalArgumentException::class.java) {
      KotlinMetadataRemapper.prepare(emptyList(),
        mapOf("META-INF/annotations.kotlin_module" to moduleWithTables(0x32, 0x02, 0x08, 0x00)))
    }
    assertTrue(error.message!!.contains("field 6"))
  }

  @Test fun malformedModuleIsRejectedBeforeChangingClasses() {
    val node = sample()
    assertThrows(IllegalArgumentException::class.java) {
      KotlinMetadataRemapper.prepare(listOf(node), mapOf("META-INF/broken.kotlin_module" to byteArrayOf(0, 1, 2)))
    }
    assertEquals("fixture/KotlinData", node.name)
  }
}