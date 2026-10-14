package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.transformer.*
import com.peco2282.obfaryn.core.context.ObfuscationContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.nio.file.Files
import java.nio.file.Path
import java.net.URLClassLoader
import java.util.Random
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.readText

class VirtualizationPhase1Test {
  @TempDir lateinit var dir: Path

  private fun fixture(): ClassNode = ClassNode().apply {
    version = Opcodes.V1_8
    access = Opcodes.ACC_PUBLIC
    name = "Calc"
    superName = "java/lang/Object"
    methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "good", "()I", null, null).apply {
      instructions.add(InsnNode(Opcodes.ICONST_1))
      instructions.add(InsnNode(Opcodes.IRETURN))
      maxStack = 1
    })
    methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "bad", "()I", null, null).apply {
      instructions.add(InsnNode(Opcodes.ICONST_1))
      instructions.add(InsnNode(Opcodes.MONITORENTER))
      instructions.add(InsnNode(Opcodes.LRETURN))
      maxStack = 1
    })
  }

  @Test fun collectsAllReasonsWithoutMutationAndUsesFullMatch() {
    val owner = fixture()
    val before = owner.methods.map { it.instructions.toArray().toList() }
    val report = VirtualizationAnalyzer(listOf("Calc\\..*")).analyze(listOf(owner))
    assertEquals(2, report.selected)
    assertEquals(1, report.eligible)
    assertEquals(1, report.rejected)
    assertEquals(1, report.methods.single { it.identity.contains("bad") }.reasons.size)
    val bad = report.methods.single { it.identity.contains("bad") }
    assertEquals(MethodIdentity("Calc", "bad", "()I"), bad.methodIdentity)
    assertEquals(listOf(1), bad.rejections.map { it.instructionIndex })
    assertEquals(listOf(Opcodes.MONITORENTER), bad.rejections.map { it.opcode })
    assertTrue(bad.rejections.all { it.reason == VirtualizationRejectionReason.UNSUPPORTED_OPCODE })
    assertEquals(listOf(VirtualizationRejectionCode.MONITOR), bad.rejections.map { it.code })
    assertEquals(before, owner.methods.map { it.instructions.toArray().toList() })
    assertEquals(0, VirtualizationAnalyzer(listOf("good")).analyze(listOf(owner)).selected)
    assertThrows(IllegalArgumentException::class.java) { report.requireEligible(VirtualizationSelectionMode.STRICT) }
    assertTrue(report.requireEligible(VirtualizationSelectionMode.ELIGIBLE).single().startsWith("Skipped:"))
    assertTrue(report.requireEligible(VirtualizationSelectionMode.ANALYZE_ONLY).isEmpty())
    val path = dir.resolve("nested/report.json")
    report.write(path)
    assertTrue(path.readText().contains("\"rejected\":1"))
    assertTrue(path.readText().contains("\"schemaVersion\":2"))
    assertEquals(2, report.estimatedInstructionCount)
    assertEquals(2, report.opcodes[Opcodes.ICONST_1])
  }

  @Test fun analyzeOnlyWritesReportButNotOutputOrMapping() {
    val input = dir.resolve("input.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      val writer = ClassWriter(0)
      fixture().accept(writer)
      jar.putNextEntry(JarEntry("Calc.class"))
      jar.write(writer.toByteArray())
      jar.closeEntry()
    }
    val output = dir.resolve("output.jar")
    val mapping = dir.resolve("mapping.txt")
    val report = dir.resolve("analysis.json")
    val result = Obfuscator(ObfuscationConfig(virtualizeMethods = listOf("Calc\\..*"),
      virtualizationAnalyzeOnly = true, virtualizationReport = report,
      integrityPrivateKey = dir.resolve("missing-key.pem")))
      .obfuscate(input, output, mapping)
    assertFalse(Files.exists(output))
    assertFalse(Files.exists(mapping))
    assertTrue(Files.exists(report))
    assertEquals(0, result.methodsVirtualized)
    assertEquals(1, result.virtualizationAnalysis!!.rejected)
  }

  @Test fun canonicalModeDefaultsAndLegacyAlias() {
    assertEquals(VirtualizationSelectionMode.STRICT, ObfuscationConfig().virtualizationSelectionMode)
    assertFalse(ObfuscationConfig().virtualizationAnalyzeOnly)
    assertEquals(VirtualizationSelectionMode.ELIGIBLE,
      ObfuscationConfig(virtualizationMode = VirtualizationSelectionMode.ELIGIBLE).virtualizationSelectionMode)
    assertEquals(VirtualizationSelectionMode.STRICT,
      ObfuscationConfig(virtualizationMode = VirtualizationSelectionMode.ELIGIBLE,
        virtualizationSelectionMode = VirtualizationSelectionMode.STRICT).virtualizationSelectionMode)
  }

  @Test fun rejectsAllUnsupportedMemberTypesAndConstantsWithIndexes() {
    val owner = fixture()
    val method = MethodNode(Opcodes.ACC_PUBLIC, "members", "(JFD)V", null, null).apply {
      instructions.add(FieldInsnNode(Opcodes.GETSTATIC, "Fields", "value", "J"))
      instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "Calls", "wide", "(DF)J", false))
      instructions.add(LdcInsnNode(org.objectweb.asm.Type.getType("Ljava/lang/String;")))
      instructions.add(InsnNode(Opcodes.RETURN))
    }
    val analysis = VirtualizationAnalyzer(emptyList()).analyze(owner, method)
    assertEquals(1, analysis.rejections.size)
    assertEquals(0, analysis.rejections.count { it.reason == VirtualizationRejectionReason.UNSUPPORTED_TYPE })
    assertEquals(2, analysis.rejections.single { it.reason == VirtualizationRejectionReason.UNSUPPORTED_CONSTANT }.instructionIndex)
    assertEquals(listOf("Fields.value:J"), analysis.fields)
    assertEquals(listOf("Calls.wide(DF)J"), analysis.invokes)
    assertEquals(listOf("Calls", "Fields"), analysis.references)
    assertEquals(1, analysis.fieldCount)
    assertEquals(1, analysis.invokeCount)
    assertEquals(4, analysis.instructionCount)
    assertEquals(2, analysis.bridgeCount)
    assertEquals(4, analysis.estimatedInstructionCount)
  }

  @Test fun referenceInstanceMethodIsEligibleAndReportedWithoutMutation() {
    val owner = fixture()
    val method = MethodNode(Opcodes.ACC_PUBLIC, "reference", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null).apply {
      instructions.add(VarInsnNode(Opcodes.ALOAD, 1))
      instructions.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Object"))
      instructions.add(InsnNode(Opcodes.ARETURN))
      maxLocals = 2
      maxStack = 1
    }
    val before = method.instructions.toArray().toList()
    val analysis = VirtualizationAnalyzer(emptyList()).analyze(owner, method)
    assertTrue(analysis.eligible, analysis.reasons.joinToString())
    assertEquals(before, method.instructions.toArray().toList())
    assertEquals(listOf("java/lang/Object"), analysis.references)
    assertEquals(listOf("Ljava/lang/Object;"), analysis.types)
    assertEquals(1, analysis.bridgeCount)
    assertEquals(3, analysis.estimatedInstructionCount)
  }

  @Test fun reportIsDeterministicAndIncludesCanonicalMetadata() {
    val owner = fixture()
    val empty = ClassNode().apply { name = "Empty" }
    val analyzer = VirtualizationAnalyzer(listOf("Calc\\..*"))
    val report = analyzer.analyze(listOf(owner, empty))
    val json = report.toJson()
    owner.methods.reverse()
    assertEquals(json, analyzer.analyze(listOf(empty, owner)).toJson())
    assertEquals(json, analyzer.analyze(listOf(owner, empty)).toJson())
    assertEquals(2, report.classesAnalyzed)
    assertEquals(2, report.methodsAnalyzed)
    assertEquals(1, report.rejectionSummary[VirtualizationRejectionCode.MONITOR])
    for (field in listOf("classesAnalyzed", "methodsAnalyzed", "rejectionSummary", "owner", "name",
      "descriptor", "isStatic", "argumentTypes", "returnType", "hasTryCatch", "hasInvokeDynamic", "code"))
      assertTrue(json.contains("\"$field\":"), field)
    val good = report.methods.single { it.name == "good" }
    assertEquals("Calc", good.owner)
    assertEquals("()I", good.descriptor)
    assertTrue(good.isStatic)
    assertEquals(emptyList<String>(), good.argumentTypes)
    assertEquals("I", good.returnType)
    assertFalse(good.hasTryCatch)
    assertFalse(good.hasInvokeDynamic)
  }

  @Test fun strictTransformerLeavesEntireContextAndMethodsUnchanged() {
    val owner = fixture()
    val context = ObfuscationContext(linkedMapOf(owner.name to owner), linkedMapOf())
    fun bytes(): List<Byte> = ClassWriter(0).also(owner::accept).toByteArray().toList()
    val before = bytes()
    val methods = owner.methods.toList()
    val bodies = methods.map { it.instructions }
    val classes = context.classes.toMap()
    val resources = context.resources.toMap()
    val transformer = VirtualizationTransformer(listOf("Calc\\..*"), Random(42))
    assertThrows(IllegalArgumentException::class.java) { transformer.transform(context) }
    assertEquals(0, transformer.count)
    assertEquals(classes, context.classes)
    assertEquals(resources, context.resources)
    assertSame(owner, context.classes["Calc"])
    assertEquals(methods, owner.methods)
    methods.forEachIndexed { index, method -> assertSame(bodies[index], method.instructions) }
    assertEquals(before, bytes())
  }

  @Test fun eligibleTransformsOnlyEligibleMethodsAndExecutesResult() {
    val owner = fixture()
    val rejected = owner.methods.single { it.name == "bad" }
    rejected.desc = "()Ljava/lang/Class;"
    rejected.instructions = InsnList().apply {
      add(LdcInsnNode(org.objectweb.asm.Type.getType("Ljava/lang/String;"))); add(InsnNode(Opcodes.ARETURN))
    }
    rejected.maxStack = 1
    val before = rejected.instructions
    val context = ObfuscationContext(linkedMapOf(owner.name to owner), linkedMapOf())
    val transformer = VirtualizationTransformer(listOf("Calc\\..*"), Random(42), 3, VirtualizationSelectionMode.ELIGIBLE)
    transformer.transform(context)
    assertEquals(1, transformer.count)
    assertSame(before, rejected.instructions)
    val jar = dir.resolve("eligible.jar")
    JarOutputStream(Files.newOutputStream(jar)).use { out ->
      for (node in context.classes.values) {
        val writer = ClassWriter(0)
        node.accept(writer)
        out.putNextEntry(JarEntry("${node.name}.class"))
        out.write(writer.toByteArray())
        out.closeEntry()
      }
    }
    URLClassLoader(arrayOf(jar.toUri().toURL()), platformClassLoader()).use { loader ->
      val loaded = loader.loadClass("Calc")
      assertEquals(1, loaded.getMethod("good").invoke(null))
      assertEquals(String::class.java, loaded.getMethod("bad").invoke(null))
    }
  }

  @Test fun noSelectionAndNoEligibleWarningsAreDistinct() {
    val analyzer = VirtualizationAnalyzer(listOf("Calc\\.bad.*"))
    val warnings = analyzer.analyze(listOf(fixture())).requireEligible(VirtualizationSelectionMode.ELIGIBLE)
    assertEquals(2, warnings.size)
    assertTrue(warnings[0].startsWith("Skipped:"))
    assertTrue(warnings[1].contains("no eligible methods"))
    val none = VirtualizationAnalyzer(emptyList()).analyze(listOf(fixture()))
      .requireEligible(VirtualizationSelectionMode.ELIGIBLE)
    assertEquals(listOf("Virtualization selectors matched no non-excluded methods"), none)
    assertNotEquals(none, warnings)
  }

  @Test fun stringConstantIsEligibleAndCountsBridge() {
    val owner = fixture()
    val method = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "text", "()Ljava/lang/String;", null, null).apply {
      instructions.add(LdcInsnNode("hello"))
      instructions.add(InsnNode(Opcodes.ARETURN))
      maxStack = 1
    }
    val analysis = VirtualizationAnalyzer(emptyList()).analyze(owner, method)
    assertTrue(analysis.eligible, analysis.reasons.joinToString())
    assertEquals(1, analysis.bridgeCount)
    assertEquals(listOf("java/lang/String"), analysis.references)
  }

  @Test fun dedicatedCodesDistinguishInitializersFlagsOpcodesAndInvalidPrograms() {
    val analyzer = VirtualizationAnalyzer(emptyList())
    val owner = fixture()
    fun codes(method: MethodNode) = analyzer.analyze(owner, method).rejections.map { it.code }
    for ((name, code) in listOf("<init>" to VirtualizationRejectionCode.CONSTRUCTOR,
      "<clinit>" to VirtualizationRejectionCode.CLASS_INITIALIZER)) {
      assertTrue(code in codes(MethodNode(0, name, "()V", null, null)))
    }
    for ((flag, code) in listOf(Opcodes.ACC_NATIVE to VirtualizationRejectionCode.NATIVE_METHOD,
      Opcodes.ACC_ABSTRACT to VirtualizationRejectionCode.ABSTRACT_METHOD)) {
      assertTrue(code in codes(MethodNode(flag, "flagged", "()V", null, null)))
    }
    val legacy = MethodNode(Opcodes.ACC_STATIC, "legacy", "()V", null, null).apply {
      instructions.add(VarInsnNode(Opcodes.RET, 0))
    }
    assertEquals(listOf(VirtualizationRejectionCode.LEGACY_SUBROUTINE), codes(legacy))
    val dynamic = MethodNode(Opcodes.ACC_STATIC, "dynamic", "()V", null, null).apply {
      instructions.add(InvokeDynamicInsnNode("run", "()V",
        org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "Bootstrap", "link", "()V", false)))
      instructions.add(InsnNode(Opcodes.RETURN))
    }
    assertTrue(codes(dynamic).isEmpty())
    assertTrue(analyzer.analyze(owner, dynamic).hasInvokeDynamic)
    assertEquals(1, analyzer.analyze(owner, dynamic).bridgeCount)
    val flow = MethodNode(Opcodes.ACC_STATIC, "flow", "()V", null, null).apply {
      instructions.add(JumpInsnNode(Opcodes.GOTO, LabelNode()))
    }
    assertEquals(listOf(VirtualizationRejectionCode.INVALID_CONTROL_FLOW), codes(flow))
    val stack = MethodNode(Opcodes.ACC_STATIC, "stack", "()I", null, null).apply {
      instructions.add(InsnNode(Opcodes.IRETURN)); maxStack = 1
    }
    assertEquals(listOf(VirtualizationRejectionCode.INVALID_STACK), codes(stack))
  }
}
