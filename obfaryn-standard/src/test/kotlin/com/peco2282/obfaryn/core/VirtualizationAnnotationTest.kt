package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.transformer.VirtualizationAnalyzer
import com.peco2282.obfaryn.core.transformer.VirtualizationSelectionMode
import com.peco2282.obfaryn.core.transformer.VirtualizationTransformer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodNode
import java.util.Random
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class VirtualizationAnnotationTest {
  @TempDir lateinit var dir: Path
  private val virtualize = "Lcom/peco2282/obfaryn/annotations/Virtualize;"
  private val hotPath = "Lcom/peco2282/obfaryn/annotations/HotPath;"

  private fun method(name: String) = MethodNode(
    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, "()I", null, null
  ).apply {
    instructions.add(InsnNode(Opcodes.ICONST_1))
    instructions.add(InsnNode(Opcodes.IRETURN))
    maxStack = 1
  }

  private fun owner(vararg methods: MethodNode) = ClassNode().apply {
    version = Opcodes.V17
    access = Opcodes.ACC_PUBLIC
    name = "Annotated"
    superName = "java/lang/Object"
    this.methods.addAll(methods)
  }

  private fun MethodNode.annotate(descriptor: String) = apply {
    invisibleAnnotations = (invisibleAnnotations ?: mutableListOf()).also { it.add(AnnotationNode(descriptor)) }
  }

  private fun ClassNode.annotate(descriptor: String) = apply {
    invisibleAnnotations = (invisibleAnnotations ?: mutableListOf()).also { it.add(AnnotationNode(descriptor)) }
  }

  @Test fun methodAnnotationsSelectAndExcludeWithoutPatterns() {
    val selected = method("selected").annotate(virtualize)
    val excluded = method("excluded").annotate(hotPath)
    val plain = method("plain")
    val report = VirtualizationAnalyzer(emptyList()).analyze(listOf(owner(selected, excluded, plain)))

    assertTrue(report.methods.single { it.name == "selected" }.selected)
    assertFalse(report.methods.single { it.name == "excluded" }.selected)
    assertEquals("@HotPath on method", report.methods.single { it.name == "excluded" }.exclusionReason)
    assertFalse(report.methods.single { it.name == "plain" }.selected)
  }

  @Test fun methodDirectiveOverridesClassDirective() {
    val forced = method("forced").annotate(virtualize)
    val inherited = method("inherited")
    val hotClass = owner(forced, inherited).annotate(hotPath)
    val hotReport = VirtualizationAnalyzer(emptyList()).analyze(listOf(hotClass))
    assertTrue(hotReport.methods.single { it.name == "forced" }.selected)
    assertEquals("@HotPath on class", hotReport.methods.single { it.name == "inherited" }.exclusionReason)

    val skipped = method("skipped").annotate(hotPath)
    val selected = method("selected")
    val virtualClass = owner(skipped, selected).annotate(virtualize)
    val virtualReport = VirtualizationAnalyzer(emptyList()).analyze(listOf(virtualClass))
    assertFalse(virtualReport.methods.single { it.name == "skipped" }.selected)
    assertTrue(virtualReport.methods.single { it.name == "selected" }.selected)
  }

  @Test fun explicitVirtualizeOverridesPlatformPolicyButNotUserExclusion() {
    val selected = method("selected").annotate(virtualize)
    val owner = owner(selected)
    val identity = "Annotated.selected()I"
    assertTrue(VirtualizationAnalyzer(emptyList(), exactExclusions = mapOf(identity to "paper hot path"))
      .analyze(listOf(owner)).methods.single().selected)
    val excluded = VirtualizationAnalyzer(emptyList(), listOf("Annotated\\.selected\\(\\)I"),
      mapOf(identity to "paper hot path")).analyze(listOf(owner)).methods.single()
    assertFalse(excluded.selected)
    assertEquals("user virtualization exclusion", excluded.exclusionReason)
  }

  @Test fun conflictingAnnotationsFailFast() {
    val conflict = method("conflict").annotate(virtualize).annotate(hotPath)
    val failure = assertThrows(IllegalArgumentException::class.java) {
      VirtualizationAnalyzer(emptyList()).analyze(listOf(owner(conflict)))
    }
    assertTrue(failure.message!!.contains("Annotated.conflict()I"))
  }

  @Test fun transformerRunsFromAnnotationOnly() {
    val owner = owner(method("selected").annotate(virtualize))
    val context = ObfuscationContext(linkedMapOf(owner.name to owner), linkedMapOf())
    val transformer = VirtualizationTransformer(emptyList(), Random(7), mode = VirtualizationSelectionMode.ELIGIBLE)
    transformer.transform(context)
    assertEquals(1, transformer.count)
  }

  @Test fun obfuscatorRunsVirtualizationFromAnnotationOnly() {
    val input = dir.resolve("annotated.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      val writer = ClassWriter(0)
      owner(method("selected").annotate(virtualize)).accept(writer)
      jar.putNextEntry(JarEntry("Annotated.class"))
      jar.write(writer.toByteArray())
      jar.closeEntry()
    }
    val output = dir.resolve("annotated-obfuscated.jar")
    val result = Obfuscator(ObfuscationConfig(
      stringEncryption = false,
      numberObfuscation = false,
      renameClasses = false,
      renameMethods = false,
      renameFields = false,
      virtualizationSelectionMode = VirtualizationSelectionMode.ELIGIBLE,
    )).obfuscate(input, output)

    assertEquals(1, result.methodsVirtualized)
    assertTrue(Files.isRegularFile(output))
    JarFile(output.toFile()).use { jar ->
      val bytes = jar.getInputStream(jar.getJarEntry("Annotated.class")).use { it.readBytes() }
      val transformed = ClassNode().also { ClassReader(bytes).accept(it, 0) }
      assertTrue(transformed.methods.single { it.name == "selected" }.instructions.size() > 2)
    }
  }
}
