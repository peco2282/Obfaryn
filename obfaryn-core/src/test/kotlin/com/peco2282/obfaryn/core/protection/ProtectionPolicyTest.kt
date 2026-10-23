package com.peco2282.obfaryn.core.protection

import com.peco2282.obfaryn.core.*
import com.peco2282.obfaryn.core.fixtures.JavaJarFixture
import com.peco2282.obfaryn.api.protection.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.tree.*
import java.net.URLClassLoader
import java.nio.file.*

class ProtectionPolicyTest {
  @TempDir lateinit var dir: Path

  @Test fun explicitRunModesOverrideStrongAndLegacyFlagsWithoutSelectingVirtualization() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Choice" to """
      package app; public class Choice {
        public static int choose(int n) { if (n > 0) return n + 3; return n - 3; }
      }
    """.trimIndent()))
    for (mode in listOf(ControlFlowMode.NONE, ControlFlowMode.LIGHT, ControlFlowMode.DISPATCH)) {
      val output = dir.resolve("mode-${mode.name}.jar")
      val result = Obfuscator(ObfuscationConfig(protectionLevel = ProtectionLevel.STRONG,
        applicationPackages = listOf("app"), keep = listOf("app.Choice"),
        stringEncryption = false, numberObfuscation = false, controlFlow = true,
        lightweightControlFlow = true, controlFlowMode = mode, seed = 1)).obfuscate(input, output)
      val flow = result.transformerReports.firstOrNull { it.id == "control-flow" }
      when (mode) {
        ControlFlowMode.NONE -> assertNull(flow)
        ControlFlowMode.LIGHT -> assertTrue(flow!!.metrics.getValue("rearrangedMethods") > 0)
        ControlFlowMode.DISPATCH -> assertTrue(flow!!.metrics.getValue("dispatchedMethods") > 0)
        else -> error("Unexpected test mode")
      }
      assertEquals(0, result.methodsVirtualized)
      URLClassLoader(arrayOf(output.toUri().toURL()), null).use { loader ->
        val method = loader.loadClass("app.Choice").getMethod("choose", Int::class.javaPrimitiveType)
        assertEquals(8, method.invoke(null, 5))
        assertEquals(-8, method.invoke(null, -5))
      }
    }
  }

  @Test fun methodOverridesClassFeatureDefaultsButNeverHotPath() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Main" to """
      package app;
      import com.peco2282.obfaryn.annotations.*;
      @Protection(stringEncryption=ProtectionMode.DISABLED, callIndirection=ProtectionMode.DISABLED,
        controlFlow=FlowProtection.LIGHT)
      public class Main {
        public static String plain() { return "plain".toUpperCase(); }
        @Protection(stringEncryption=ProtectionMode.ENABLED,callIndirection=ProtectionMode.ENABLED)
        public static String protectedText() { return "secret".toUpperCase(); }
        @HotPath @Protection(stringEncryption=ProtectionMode.ENABLED,callIndirection=ProtectionMode.ENABLED)
        public static String hot() { return "hot".toUpperCase(); }
        public static int choose(int n) { return n > 2 ? n + 2 : n - 3; }
      }
    """.trimIndent()))
    val output = dir.resolve("out.jar")
    val result = Obfuscator(ObfuscationConfig(controlFlow = true, callIndirection = true,
      applicationPackages = listOf("app"), keep = listOf("app.Main"), seed = 1, verifyOutput = true,
      transformationReport = dir.resolve("decisions.json"))).obfuscate(input, output)
    val owner = JavaJarFixture.classes(output).single { it.name == "app/Main" }
    fun calls(name: String) = owner.methods.single { it.name == name }.instructions.toArray().filterIsInstance<InvokeDynamicInsnNode>()
    assertTrue(calls("plain").isEmpty()); assertTrue(calls("hot").isEmpty()); assertTrue(calls("protectedText").isNotEmpty())
    assertFalse(owner.methods.single { it.name == "choose" }.instructions.any { it is LookupSwitchInsnNode })
    assertTrue(result.transformerReports.flatMap { it.methods }.any { it.reason == "feature-policy:stringEncryption" })
    URLClassLoader(arrayOf(output.toUri().toURL()), null).use { loader ->
      val cls = loader.loadClass("app.Main")
      for (name in listOf("plain", "hot", "protectedText")) assertEquals(
        mapOf("plain" to "PLAIN", "hot" to "HOT", "protectedText" to "SECRET")[name], cls.getMethod(name).invoke(null))
    }
  }

  @Test fun rulesAndReportIdentitiesSurviveClassAndMethodRenaming() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Worker" to """
      package app; public class Worker { public static int compute(int n) { return Math.abs(n); } }
    """.trimIndent()))
    val output = dir.resolve("renamed.jar")
    val result = Obfuscator(ObfuscationConfig(callIndirection = true, applicationPackages = listOf("app"),
      seed = 1, protectionRules = listOf(ProtectionRule("app/Worker\\.compute\\(I\\)I", mapOf(BodyFeature.CALLS to false))),
      transformationReport = dir.resolve("report.json"), verifyOutput = true)).obfuscate(input, output)
    assertTrue(result.classesRenamed > 0)
    assertFalse(JavaJarFixture.classes(output).flatMap { it.methods }.any { it.instructions.any { n -> n is InvokeDynamicInsnNode } })
    val rows = result.transformerReports.single { it.id == "call-indirection" }.methods
    assertTrue(rows.any { it.method == "app/Worker.compute(I)I" && it.reason == "feature-policy:callIndirection" })
  }

  @Test fun rejectedClassRollsBackConstantsInitializersAndHelpersTogether() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Main" to """
      package app; public class Main {
        public static final String SECRET = "secret";
        public static String value() { return SECRET.toUpperCase(); }
      }
    """.trimIndent()))
    val output = dir.resolve("limited.jar")
    val result = Obfuscator(ObfuscationConfig(applicationPackages = listOf("app"), keep = listOf("app.Main"),
      transformationBudget = TransformationBudget(maxAddedInstructions = 0),
      transformationReport = dir.resolve("limited.json"), verifyOutput = true)).obfuscate(input, output)
    val owner = JavaJarFixture.classes(output).single { it.name == "app/Main" }
    assertEquals("secret", owner.fields.single { it.name == "SECRET" }.value)
    assertEquals(0, result.stringsEncrypted)
    assertFalse(owner.fields.any { it.name != "SECRET" })
    assertTrue(result.transformerReports.flatMap { it.methods }.any { it.status == "rejected" && it.reason.startsWith("class-rollback:budget:") })
    URLClassLoader(arrayOf(output.toUri().toURL()), null).use { assertEquals("SECRET", it.loadClass("app.Main").getMethod("value").invoke(null)) }
  }

  @Test fun annotationBudgetsInheritPerPropertyAndMethodFeatureOverridesWinOverRules() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Main" to """
      package app; import com.peco2282.obfaryn.annotations.*;
      @Protection(integerArithmetic=ProtectionMode.DISABLED)
      @PerformanceBudget(maxInputInstructions=1,maxAddedInstructions=0)
      public class Main {
        public static int plain(int a,int b) { return a+b; }
        @Protection(integerArithmetic=ProtectionMode.ENABLED)
        public static int inputLimited(int a,int b) { return a+b; }
        @Protection(integerArithmetic=ProtectionMode.ENABLED)
        @PerformanceBudget(maxInputInstructions=100,maxAddedInstructions=16)
        public static int allowed(int a,int b) { return a+b; }
      }
    """.trimIndent()))
    val output = dir.resolve("inherited.jar")
    val result = Obfuscator(ObfuscationConfig(integerArithmetic = true, applicationPackages = listOf("app"),
      keep = listOf("app.Main"), stringEncryption = false, numberObfuscation = false,
      protectionRules = listOf(ProtectionRule("app/Main\\.allowed.*", mapOf(BodyFeature.INTEGER_ARITHMETIC to false))),
      transformationReport = dir.resolve("inherited.json"), verifyOutput = true)).obfuscate(input, output)
    val rows = result.transformerReports.single { it.id == "integer-arithmetic" }.methods
    assertTrue(rows.any { it.method.contains(".allowed(") && it.status == "applied" })
    assertTrue(rows.any { it.method.contains(".inputLimited(") && it.reason == "budget:input-instructions" })
    assertTrue(rows.any { it.method.contains(".plain(") && it.reason == "feature-policy:integerArithmetic" })
    URLClassLoader(arrayOf(output.toUri().toURL()), null).use { loader ->
      assertEquals(-1, loader.loadClass("app.Main").getMethod("allowed", Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType).invoke(null, Int.MIN_VALUE, Int.MAX_VALUE))
    }
  }

  @Test fun growthBudgetIsCumulativeAcrossPasses() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Main" to
      "package app; public class Main { public static int compute(int x) { return x+17; } }"))
    val output = dir.resolve("cumulative.jar")
    val strategy = object : com.peco2282.obfaryn.api.transformer.NumberObfuscationStrategy {
      override fun generateInt(value: Int, random: java.util.Random) = InsnList().apply {
        add(LdcInsnNode(value xor 123)); add(LdcInsnNode(123)); add(InsnNode(org.objectweb.asm.Opcodes.IXOR))
      }
      override fun generateLong(value: Long, random: java.util.Random): InsnList? = null
    }
    val result = Obfuscator(ObfuscationConfig(integerArithmetic = true, maxArithmeticOperationsPerMethod = 1,
      applicationPackages = listOf("app"), keep = listOf("app.Main"), stringEncryption = false,
      numberStrategy = strategy, transformationBudget = TransformationBudget(maxAddedInstructions = 4),
      transformationReport = dir.resolve("cumulative.json"), verifyOutput = true)).obfuscate(input, output)
    assertEquals(1, result.constantsObfuscated)
    val arithmetic = result.transformerReports.single { it.id == "integer-arithmetic" }
    assertEquals(0L, arithmetic.metrics["transformed"])
    assertTrue(arithmetic.methods.any { it.status == "rejected" && it.reason.contains("budget:added-instructions") })
    URLClassLoader(arrayOf(output.toUri().toURL()), null).use {
      assertEquals(25, it.loadClass("app.Main").getMethod("compute", Int::class.javaPrimitiveType).invoke(null, 8))
    }
  }

  @Test fun customIsolatedPassCannotLeakHelperThroughPlusAssignOnRollback() {
    val input = JavaJarFixture.compile(dir, mapOf("app/Main" to
      "package app; public class Main { public static int value(int x) { return x; } }"))
    val output = dir.resolve("custom.jar")
    val config = ObfuscationConfig(stringEncryption = false, numberObfuscation = false,
      renameClasses = false, renameMethods = false, renameFields = false,
      transformationBudget = TransformationBudget(maxAddedInstructions = 0))
    Obfuscator(config, configurePipeline = {
      add(object : com.peco2282.obfaryn.api.transformer.Transformer {
        override val id = "custom-local"
        override val isolatedBodyFeature = BodyFeature.INTEGER_ARITHMETIC
        override fun transform(context: com.peco2282.obfaryn.api.ObfuscationContext) {
          val owner = context.classes.values.single()
          val method = owner.methods.single { it.name == "value" }
          if (!context.transformationPolicy.canTransformBody(owner, method, BodyFeature.INTEGER_ARITHMETIC)) return
          method.instructions.insert(InsnNode(org.objectweb.asm.Opcodes.NOP))
          context += ClassNode().apply {
            visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC,
              "generated/Helper", null, "java/lang/Object", null)
          }
        }
      })
    }).obfuscate(input, output)
    assertEquals(listOf("app/Main"), JavaJarFixture.classes(output).map { it.name })
  }

  @Test fun invalidBudgetsAndReportCollisionsFailBeforeOutput() {
    assertThrows(IllegalArgumentException::class.java) { TransformationBudget(maxGrowthPercent = 99) }
    val input = JavaJarFixture.compile(dir, mapOf("app/Main" to """
      package app; import com.peco2282.obfaryn.annotations.*;
      @PerformanceBudget(maxAddedInstructions=-2) public class Main { public static int f() { return 3; } }
    """.trimIndent()))
    val output = dir.resolve("invalid.jar")
    assertThrows(IllegalArgumentException::class.java) { Obfuscator().obfuscate(input, output) }
    assertFalse(Files.exists(output))
    assertThrows(IllegalArgumentException::class.java) {
      Obfuscator(ObfuscationConfig(transformationReport = input)).obfuscate(input, output)
    }
    assertTrue(Files.size(input) > 0)
  }
}
