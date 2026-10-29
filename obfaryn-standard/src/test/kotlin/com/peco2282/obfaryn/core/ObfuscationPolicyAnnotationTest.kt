package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy
import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.transformer.NumberObfuscationTransformer
import com.peco2282.obfaryn.core.transformer.VirtualizationAnalyzer
import com.peco2282.obfaryn.core.transformer.VirtualizationSelectionMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.readText

class ObfuscationPolicyAnnotationTest {
  @TempDir lateinit var dir: Path

  private fun method(name: String, annotation: String? = null) = MethodNode(
    Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, name, "()I", null, null
  ).apply {
    annotation?.let { invisibleAnnotations = mutableListOf(AnnotationNode(it)) }
    instructions.add(LdcInsnNode("secret-$name"))
    instructions.add(InsnNode(Opcodes.POP))
    instructions.add(IntInsnNode(Opcodes.BIPUSH, 42))
    instructions.add(InsnNode(Opcodes.IRETURN))
    maxStack = 1
  }

  private fun owner(name: String = "sample/Sample", vararg methods: MethodNode) = ClassNode().apply {
    version = Opcodes.V17
    access = Opcodes.ACC_PUBLIC
    this.name = name
    superName = "java/lang/Object"
    this.methods.addAll(methods)
  }

  private fun writeJar(path: Path, vararg nodes: ClassNode) {
    JarOutputStream(Files.newOutputStream(path)).use { jar ->
      for (node in nodes) {
        val writer = ClassWriter(0)
        node.accept(writer)
        jar.putNextEntry(JarEntry("${node.name}.class"))
        jar.write(writer.toByteArray())
        jar.closeEntry()
      }
    }
  }

  @Test fun renameOnlyAndNoObfuscationSkipBodyVirtualization() {
    val renameOnly = method("renameOnly", ObfuscationAnnotationPolicy.RENAME_ONLY)
    val noObfuscation = method("noObfuscation", ObfuscationAnnotationPolicy.NO_OBFUSCATION)
    val owner = owner(methods = arrayOf(renameOnly, noObfuscation))
    val report = VirtualizationAnalyzer(listOf("sample/Sample\\..*")).analyze(listOf(owner))

    assertEquals("@RenameOnly on method", report.methods.single { it.name == "renameOnly" }.exclusionReason)
    assertEquals("@NoObfuscation on method", report.methods.single { it.name == "noObfuscation" }.exclusionReason)

    val before = listOf(renameOnly, noObfuscation).associate { it.name to it.instructions.toArray().toList() }
    NumberObfuscationTransformer(Random(1)).transform(
      ObfuscationContext(linkedMapOf(owner.name to owner), linkedMapOf())
    )
    assertEquals(before["renameOnly"], renameOnly.instructions.toArray().toList())
    assertEquals(before["noObfuscation"], noObfuscation.instructions.toArray().toList())
  }

  @Test fun fullPipelineRenamesRenameOnlyButKeepsNoObfuscationNameAndBody() {
    val input = dir.resolve("policy.jar")
    val renameOnly = method("renameOnly", ObfuscationAnnotationPolicy.RENAME_ONLY)
    val noObfuscation = method("noObfuscation", ObfuscationAnnotationPolicy.NO_OBFUSCATION)
    val keepName = method("keepName", ObfuscationAnnotationPolicy.KEEP_NAME)
    val normal = method("normal")
    val excludedClass = owner("sample/Excluded", method("untouched")).apply {
      invisibleAnnotations = mutableListOf(AnnotationNode(ObfuscationAnnotationPolicy.NO_OBFUSCATION))
    }
    writeJar(input, owner(methods = arrayOf(renameOnly, noObfuscation, keepName, normal)), excludedClass)

    val output = dir.resolve("policy-output.jar")
    val mapping = dir.resolve("policy-mapping.txt")
    val result = Obfuscator(ObfuscationConfig(
      applicationPackages = listOf("sample"),
      numberObfuscation = false,
      controlFlow = false,
      virtualizationSelectionMode = VirtualizationSelectionMode.ELIGIBLE,
      seed = 4,
    )).obfuscate(input, output, mapping)

    val mappingText = mapping.readText()
    assertEquals(2, result.stringsEncrypted)
    assertTrue(mappingText.contains("sample/Sample.renameOnly()I ->"))
    assertTrue(mappingText.contains("sample/Sample.normal()I ->"))
    assertFalse(mappingText.contains("sample/Sample.noObfuscation()I ->"))
    assertFalse(mappingText.contains("sample/Sample.keepName()I ->"))
    assertFalse(mappingText.contains("sample/Excluded ->"))
  }

  @Test fun contradictoryPolicyAnnotationsFailFast() {
    val conflict = method("conflict").apply {
      invisibleAnnotations = mutableListOf(
        AnnotationNode(ObfuscationAnnotationPolicy.NO_OBFUSCATION),
        AnnotationNode(ObfuscationAnnotationPolicy.RENAME_ONLY),
      )
    }
    val failure = assertThrows(IllegalArgumentException::class.java) {
      ObfuscationAnnotationPolicy.validate(listOf(owner(methods = arrayOf(conflict))))
    }
    assertTrue(failure.message!!.contains("sample/Sample.conflict()I"))
  }
}
