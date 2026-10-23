package com.peco2282.obfaryn.cli

import com.peco2282.obfaryn.api.protection.BodyFeature
import com.peco2282.obfaryn.api.protection.ControlFlowMode
import com.peco2282.obfaryn.core.KotlinMetadataMode
import com.peco2282.obfaryn.core.transformer.VirtualizationSelectionMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

class CliParsingTest {
  @TempDir lateinit var dir: Path

  private data class Result(val status: Int, val out: String, val err: String)

  private fun run(vararg args: String): Result {
    val out = StringWriter()
    val err = StringWriter()
    val status = runCli(arrayOf(*args), PrintWriter(out, true), PrintWriter(err, true))
    return Result(status, out.toString(), err.toString())
  }

  private fun input(name: String = "input.jar"): Path = dir.resolve(name).also { path ->
    JarOutputStream(Files.newOutputStream(path)).use { jar ->
      jar.putNextEntry(JarEntry("resource.txt"))
      jar.write("unchanged resource".toByteArray())
      jar.closeEntry()
    }
  }

  @Test fun helpSucceedsWithoutRequiredInputs() {
    for (args in listOf(arrayOf("--help"), arrayOf("-h"), arrayOf("obfuscate", "--help"),
      arrayOf("deobfuscate-stacktrace", "--help"), arrayOf("completion", "--help"))) {
      val result = run(*args)
      assertEquals(0, result.status, result.err)
      assertTrue(result.out.contains("Usage: obfaryn"), result.out)
      assertEquals("", result.err)
    }
    val help = run("--help").out
    assertTrue(help.contains("--keep-debug-info"))
    assertTrue(help.contains("--no-string-constant-fields"))
    assertTrue(help.contains("--no-number-constant-fields"))
  }

  @Test fun explicitModesAndFeatureExclusionsReachConfigurationInBothInvocations() {
    for (prefix in listOf(emptyList(), listOf("obfuscate"))) {
      val cli = cliCommandLine()
      val parsed = cli.parseArgs(*(prefix + listOf("input.jar", "--protection", "strong",
        "--lightweight-control-flow", "--control-flow-mode", "none",
        "--disable-feature", "calls=example/Physics\\.step\\(I\\)I",
        "--disable-feature", "integer-arithmetic=example/.*", "--no-rename-methods")).toTypedArray())
      val command = if (prefix.isEmpty()) cli.getCommand<RootCommand>().obfuscation
        else parsed.subcommand().commandSpec().userObject() as ObfuscateCommand
      val config = command.configuration()
      assertEquals(ControlFlowMode.NONE, config.effectiveControlFlowMode)
      assertEquals(2, config.protectionRules.size)
      assertEquals(mapOf(BodyFeature.CALLS to false), config.protectionRules[0].features)
      assertTrue(Regex(config.protectionRules[0].methodPattern).matches("example/Physics.step(I)I"))
      assertTrue(config.renameClasses)
      assertFalse(config.renameMethods)
      assertTrue(config.renameFields)
    }
  }

  @Test fun invalidFeatureRulesFailBeforeWritingArtifacts() {
    val input = input()
    for (value in listOf("calls", "calls=", "unknown=.*", "strings=[")) {
      assertEquals(2, run(input.toString(), "--disable-feature", value).status, value)
      assertFalse(Files.exists(dir.resolve("input-obfuscated.jar")))
    }
    assertEquals(2, run(input.toString(), "--control-flow-mode", "invalid").status)
  }

  @Test fun explicitNoneOverridesStrongAndFeatureRulesAffectTheEmittedJar() {
    val source = dir.resolve("App.java")
    source.writeText("""
      package example;
      public class App {
        public static String first() { return "first secret"; }
        public static String second() { return "second secret"; }
      }
    """.trimIndent())
    val classes = dir.resolve("classes")
    Files.createDirectories(classes)
    assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
      "--release", "8", "-d", classes.toString(), source.toString()))
    val input = dir.resolve("application.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      jar.putNextEntry(JarEntry("example/App.class"))
      jar.write(Files.readAllBytes(classes.resolve("example/App.class")))
      jar.closeEntry()
    }
    val result = run(input.toString(), "--platform", "generic", "--application-package", "example",
      "--protection", "strong", "--control-flow-mode", "none", "--no-rename",
      "--disable-feature", "strings=example/App\\.first\\(\\)Ljava/lang/String;")
    assertEquals(0, result.status, result.err)
    assertFalse(result.out.contains("Transformer control-flow:"), result.out)
    val output = dir.resolve("application-obfuscated.jar")
    java.util.jar.JarFile(output.toFile()).use { jar ->
      val bytes = jar.getInputStream(jar.getJarEntry("example/App.class")).readBytes()
      val text = String(bytes, Charsets.ISO_8859_1)
      assertTrue(text.contains("first secret"))
      assertFalse(text.contains("second secret"))
    }
    java.net.URLClassLoader(arrayOf(output.toUri().toURL()), ClassLoader.getSystemClassLoader().parent).use { loader ->
      val type = loader.loadClass("example.App")
      assertEquals("first secret", type.getMethod("first").invoke(null))
      assertEquals("second secret", type.getMethod("second").invoke(null))
    }
  }

  @Test fun validationErrorsReturnWithoutExitingJvm() {
    for (args in listOf(emptyArray(), arrayOf("obfuscate"), arrayOf("input.jar", "--unknown"), arrayOf("input.jar", "--output"),
      arrayOf("--seed=not-a-number", "input.jar"), arrayOf("--virtualization-mode=other", "input.jar"),
      arrayOf("--platform-option=broken", "input.jar"), arrayOf("--platform-option==empty-key", "input.jar"),
      arrayOf("deobfuscate-stacktrace", "trace.txt"), arrayOf("completion", "fish"))) {
      val result = run(*args)
      assertEquals(2, result.status, args.joinToString(" "))
      assertTrue(result.err.isNotBlank(), args.joinToString(" "))
      assertEquals("", result.out)
    }
    val result = run("--virtualization-runtime-group-size=0", input().toString())
    assertEquals(2, result.status)
    assertTrue(result.err.contains("--virtualization-runtime-group-size must be positive"), result.err)
    assertFalse(Files.exists(dir.resolve("input-obfuscated.jar")))
    assertTrue(run(dir.resolve("missing.jar").toString()).err.contains("Input JAR does not exist"))
  }

  @Test fun legacyInvocationUsesSiblingDefaultOutputAndAllowsOptionsFirst() {
    val input = input("a file.jar")
    val result = run("--seed=42", "--no-rename", input.toString(), "--verify-output")
    assertEquals(0, result.status, result.err)
    val output = dir.resolve("a file-obfuscated.jar")
    assertTrue(Files.isRegularFile(output))
    assertTrue(result.out.contains("Output: $output"), result.out)
    assertTrue(Files.isRegularFile(input))
  }

  @Test fun explicitObfuscateSupportsEqualsAndDefaultOutput() {
    val input = input()
    val result = run("obfuscate", "--no-rename", input.toString(), "--seed=5")
    assertEquals(0, result.status, result.err)
    assertTrue(Files.isRegularFile(dir.resolve("input-obfuscated.jar")))
    val explicit = dir.resolve("explicit.jar")
    assertEquals(0, run("obfuscate", "--output=$explicit", input.toString()).status)
    assertTrue(Files.isRegularFile(explicit))
  }

  @Test fun argumentFilesSupportQuotedPathsCommentsAndRepeatableOptions() {
    val input = input("file with spaces.jar")
    val report = dir.resolve("report with spaces.json")
    val args = dir.resolve("arguments.txt")
    args.writeText("""
      # A comment, not an argument
      obfuscate
      --dry-run
      --seed=42
      --application-package example.first
      --application-package example.second
      --report "$report"
      "$input"
    """.trimIndent())
    val result = run("@$args")
    assertEquals(0, result.status, result.err)
    assertTrue(report.readText().contains("\"dryRun\": true"))
    assertFalse(Files.exists(dir.resolve("file with spaces-obfuscated.jar")))
  }

  @Test fun summaryAndDetailedReportCannotOverwriteOneAnotherOrTheInput() {
    val input = input()
    val shared = dir.resolve("shared.json")
    val before = Files.readAllBytes(input)
    val collision = run(input.toString(), "--report", shared.toString(), "--transformation-report", shared.toString())
    assertEquals(2, collision.status)
    assertTrue(collision.err.contains("--report must use a different file"))
    assertFalse(Files.exists(shared))
    assertEquals(2, run(input.toString(), "--report", input.toString()).status)
    assertArrayEquals(before, Files.readAllBytes(input))
  }

  @Test fun everyExistingOptionAndNewSwitchesReachConfiguration() {
    val cli = cliCommandLine()
    cli.parseArgs("input.jar", "-o", "out.jar", "--mapping", "mapping.txt", "--report", "report.json",
      "--platform", "generic", "--platform-option", "mode=first", "--platform-option=mode=second=value", "--platform-option", "empty=",
      "--seed", "-10", "--keep", "example.Keep", "--keep", "example.Keep#method()V",
      "--exclude-class", "excluded/.*", "--exclude-resource", "private/.*", "--exclude-renaming", "stable/.*",
      "--target-package", "hidden", "--flatten-packages", "--control-flow", "--call-indirection", "--prelink-calls", "--integer-arithmetic", "--arithmetic-operations", "4",
      "--max-input-instructions", "1000", "--max-input-branches", "64", "--max-added-instructions", "128",
      "--max-growth-percent", "200", "--max-output-code-bytes", "60000", "--transformation-report", "methods.json",
      "--integrity-private-key", "private.der", "--integrity-public-key", "public.der",
      "--virtualize-method", "example/Calc.*", "--exclude-virtualization", ".*skip.*", "--virtualization-mode", "eligible",
      "--virtualization-analyze-only", "--virtualization-report", "analysis.json", "--virtualization-runtime-group-size", "3",
      "--classpath", "one.jar", "--classpath", "two", "--recompute-frames", "--no-string-encryption", "--no-number-obfuscation",
      "--no-string-constant-fields", "--no-number-constant-fields", "--no-rename", "--strip-kotlin-metadata",
      "--preserve-kotlin-metadata", "--application-package", "example", "--application-package", "other",
      "--verify-output", "--dry-run", "--strip-generic-signatures", "--keep-method-parameters", "--keep-debug-info")
    val command = cli.getCommand<RootCommand>().obfuscation
    val config = command.configuration()
    assertEquals("input.jar", command.input.toString())
    assertEquals("out.jar", command.output.toString())
    assertEquals("mapping.txt", command.mapping.toString())
    assertEquals("report.json", command.report.toString())
    assertEquals("generic", config.platform)
    assertEquals(mapOf("mode" to "second=value", "empty" to ""), config.platformOptions)
    assertEquals(-10L, config.seed)
    assertEquals(listOf("example.Keep", "example.Keep#method()V"), config.keep)
    assertEquals(listOf("excluded/.*"), config.excludeClasses)
    assertEquals(listOf("private/.*"), config.excludeResources)
    assertEquals(listOf("stable/.*"), config.excludeRenaming)
    assertEquals("hidden", config.targetPackage)
    assertTrue(config.flattenPackages)
    assertTrue(config.controlFlow)
    assertTrue(config.callIndirection)
    assertTrue(config.prelinkCalls)
    assertTrue(config.integerArithmetic)
    assertEquals(4, config.maxArithmeticOperationsPerMethod)
    assertEquals(1000, config.transformationBudget.maxInputInstructions)
    assertEquals(64, config.transformationBudget.maxInputBranches)
    assertEquals(128, config.transformationBudget.maxAddedInstructions)
    assertEquals(200, config.transformationBudget.maxGrowthPercent)
    assertEquals(60000, config.transformationBudget.maxOutputCodeBytes)
    assertEquals("methods.json", config.transformationReport.toString())
    assertEquals("private.der", config.integrityPrivateKey.toString())
    assertEquals("public.der", config.integrityPublicKey.toString())
    assertEquals(listOf("example/Calc.*"), config.virtualizeMethods)
    assertEquals(listOf(".*skip.*"), config.excludeVirtualization)
    assertEquals(VirtualizationSelectionMode.ELIGIBLE, config.virtualizationSelectionMode)
    assertTrue(config.virtualizationAnalyzeOnly)
    assertEquals("analysis.json", config.virtualizationReport.toString())
    assertEquals(3, config.virtualizationRuntimeGroupSize)
    assertEquals(listOf("one.jar", "two"), config.classpath.map { it.toString() })
    assertTrue(config.recomputeFrames)
    assertFalse(config.stringEncryption)
    assertFalse(config.numberObfuscation)
    assertFalse(config.stringConstantFields)
    assertFalse(config.numberConstantFields)
    assertFalse(config.renameClasses)
    assertFalse(config.renameMethods)
    assertFalse(config.renameFields)
    assertFalse(config.stripKotlinMetadata)
    assertEquals(KotlinMetadataMode.PRESERVE, config.kotlinMetadataMode)
    assertEquals(listOf("example", "other"), config.applicationPackages)
    assertTrue(config.verifyOutput)
    assertTrue(config.dryRun)
    assertTrue(config.stripGenericSignatures)
    assertFalse(config.stripMethodParameters)
    assertFalse(config.stripDebugInfo)
  }

  @Test fun defaultsMatchExistingConfigurationAndMetadataLastFlagWins() {
    val cli = cliCommandLine()
    cli.parseArgs("input.jar")
    val defaults = cli.getCommand<RootCommand>().obfuscation.configuration()
    assertTrue(defaults.stripDebugInfo)
    assertTrue(defaults.stringEncryption)
    assertTrue(defaults.numberObfuscation)
    assertTrue(defaults.stringConstantFields)
    assertTrue(defaults.numberConstantFields)
    assertTrue(defaults.renameClasses && defaults.renameMethods && defaults.renameFields)
    assertTrue(defaults.stripMethodParameters)
    assertFalse(defaults.stripKotlinMetadata)
    assertEquals(KotlinMetadataMode.AUTO, defaults.kotlinMetadataMode)
    assertEquals(VirtualizationSelectionMode.STRICT, defaults.virtualizationSelectionMode)
    val other = cliCommandLine()
    other.parseArgs("input.jar", "--preserve-kotlin-metadata", "--strip-kotlin-metadata")
    assertTrue(other.getCommand<RootCommand>().obfuscation.configuration().stripKotlinMetadata)
  }

  @Test fun repeatedScalarOptionsKeepTheLastValueInBothInvocationStyles() {
    for (prefix in listOf(emptyList(), listOf("obfuscate"))) {
      val cli = cliCommandLine()
      val parsed = cli.parseArgs(*(prefix + listOf("input.jar", "--seed=1", "--seed=2", "-o", "one.jar", "--output=two.jar")).toTypedArray())
      val command = if (prefix.isEmpty()) cli.getCommand<RootCommand>().obfuscation
        else parsed.subcommand().commandSpec().userObject() as ObfuscateCommand
      assertEquals(2L, command.configuration().seed)
      assertEquals("two.jar", command.output.toString())
    }
  }

  @Test fun deobfuscateStacktraceAcceptsMappingAfterInputAndEquals() {
    val mapping = dir.resolve("mapping.txt")
    mapping.writeText("example/Original -> hidden/A\n")
    val trace = dir.resolve("stacktrace.txt")
    trace.writeText("at hidden.A.call(Unknown Source)\n")
    for (args in listOf(arrayOf("deobfuscate-stacktrace", "--mapping", mapping.toString(), trace.toString()),
      arrayOf("deobfuscate-stacktrace", trace.toString(), "--mapping=$mapping"))) {
      val result = run(*args)
      assertEquals(0, result.status, result.err)
      assertTrue(result.out.contains("example.Original.call"), result.out)
    }
  }

  @Test fun completionScriptsContainOptionsEnumsFilesAndZshInitialization() {
    for (shell in listOf("bash", "zsh")) {
      val result = run("completion", shell)
      assertEquals(0, result.status, result.err)
      assertEquals("", result.err)
      for (text in listOf("--keep-debug-info", "--output", "--no-string-constant-fields", "--no-number-constant-fields",
        "strict", "eligible", "obfuscate", "deobfuscate-stacktrace", "compgen -f", "complete -F _complete_obfaryn")) {
        assertTrue(result.out.contains(text), "$shell: missing $text")
      }
      if (shell == "zsh") assertTrue(result.out.startsWith("autoload -Uz compinit bashcompinit\ncompinit\nbashcompinit\n"))
    }
  }

  private fun complete(script: Path, vararg words: String): List<String> {
    val command = """
      source "${'$'}1"
      shift
      COMP_WORDS=("${'$'}@")
      COMP_CWORD=${'$'}((${ '$' }# - 1))
      _complete_obfaryn
      printf '%s\n' "${'$'}{COMPREPLY[@]}"
    """.trimIndent()
    val process = ProcessBuilder(listOf("bash", "--noprofile", "--norc", "-c", command, "completion-test", script.toString()) + words)
      .directory(dir.toFile()).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), output)
    return output.lines().filter { it.isNotBlank() }
  }

  @Test fun generatedBashCompletionActuallyCompletesLegacyAndSubcommandInvocations() {
    val script = dir.resolve("obfaryn.bash")
    script.writeText(run("completion", "bash").out)
    input("fixture.jar")
    for (prefix in listOf(listOf("obfaryn"), listOf("obfaryn", "obfuscate"), listOf("obfaryn", "fixture.jar"))) {
      assertTrue(complete(script, *(prefix + "--keep-d").toTypedArray()).contains("--keep-debug-info"), prefix.toString())
      assertTrue(complete(script, *(prefix + listOf("--virtualization-mode", "e")).toTypedArray()).contains("eligible"), prefix.toString())
      assertTrue(complete(script, *(prefix + listOf("--output", "fix")).toTypedArray()).contains("fixture.jar"), prefix.toString())
    }
    assertTrue(complete(script, "obfaryn", "fix").contains("fixture.jar"))
    assertTrue(complete(script, "obfaryn", "obfuscate", "fix").contains("fixture.jar"))
    assertTrue(complete(script, "obfaryn", "completion", "z").contains("zsh"))
  }
}
