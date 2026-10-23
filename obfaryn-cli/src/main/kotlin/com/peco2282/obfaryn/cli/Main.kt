package com.peco2282.obfaryn.cli

import com.peco2282.obfaryn.core.ObfuscationConfig
import com.peco2282.obfaryn.core.KotlinMetadataMode
import com.peco2282.obfaryn.core.ProtectionLevel
import com.peco2282.obfaryn.core.Obfuscator
import com.peco2282.obfaryn.api.protection.BodyFeature
import com.peco2282.obfaryn.api.protection.ControlFlowMode
import com.peco2282.obfaryn.api.protection.ProtectionRule
import com.peco2282.obfaryn.core.mapping.Mapping
import com.peco2282.obfaryn.core.platform.Platforms
import com.peco2282.obfaryn.core.transformer.VirtualizationSelectionMode
import picocli.AutoComplete
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.Spec
import picocli.CommandLine.Model.CommandSpec
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Callable
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  val status = runCli(args)
  if (status != 0) exitProcess(status)
}

fun runCli(
  args: Array<String>,
  out: PrintWriter = PrintWriter(System.out, true),
  err: PrintWriter = PrintWriter(System.err, true),
): Int = cliCommandLine().apply {
  setOut(out)
  setErr(err)
  setExecutionExceptionHandler { exception, command, _ ->
    command.err.println("Error: ${exception.message ?: exception.javaClass.simpleName}")
    2
  }
}.execute(*args)

internal fun cliCommandLine(): CommandLine = CommandLine(RootCommand()).apply {
  isCaseInsensitiveEnumValuesAllowed = true
  isOverwrittenOptionsAllowed = true
}

@Command(
  name = "obfaryn", mixinStandardHelpOptions = true,
  description = ["Obfuscate a JAR. The default output is INPUT-obfuscated.jar beside the input."],
  footer = ["Options may precede or follow INPUT. Use @args.txt to read arguments from a file."],
  subcommands = [ObfuscateCommand::class, DeobfuscateCommand::class, CompletionCommand::class],
)
internal class RootCommand : Callable<Int> {
  @Mixin var obfuscation = ObfuscateCommand()
  override fun call(): Int = obfuscation.call()
}

@Command(name = "obfuscate", mixinStandardHelpOptions = true, description = ["Obfuscate a JAR."])
internal open class ObfuscateCommand : Callable<Int> {
  @Spec lateinit var spec: CommandSpec

  @Parameters(index = "0", arity = "0..1", paramLabel = "INPUT", description = ["Input JAR (required for obfuscation)."])
  lateinit var input: Path

  @Option(names = ["-o", "--output"], paramLabel = "JAR", description = ["Output JAR (default: INPUT-obfuscated.jar)."])
  var output: Path? = null
  @Option(names = ["--mapping"], paramLabel = "FILE", description = ["Write the rename mapping."])
  var mapping: Path? = null
  @Option(names = ["--report"], paramLabel = "FILE", description = ["Write a JSON summary."])
  var report: Path? = null
  @Option(names = ["--platform"], paramLabel = "ID", completionCandidates = PlatformCandidates::class, description = ["Platform ID (default: auto)."])
  var platform = "auto"
  @Option(names = ["--platform-option"], paramLabel = "KEY=VALUE", converter = [PlatformOptionConverter::class], description = ["Platform option; repeatable."])
  var platformOptions = mutableListOf<PlatformOption>()
  @Option(names = ["--protection"], paramLabel = "LEVEL", description = ["Protection level: standard (default) or strong; strong requires application roots and enables control flow and output checks; VM selection stays explicit."])
  var protectionLevel = ProtectionLevel.STANDARD
  @Option(names = ["--seed"], paramLabel = "N", description = ["Seed for reproducible obfuscation."])
  var seed: Long? = null
  @Option(names = ["--keep"], paramLabel = "RULE", description = ["Keep class, package.**, class#method(desc), or class#field; repeatable."])
  var keep = mutableListOf<String>()
  @Option(names = ["--exclude-class"], paramLabel = "REGEX", description = ["Exclude matching classes; repeatable."])
  var excludeClasses = mutableListOf<String>()
  @Option(names = ["--exclude-resource"], paramLabel = "REGEX", description = ["Exclude matching resources; repeatable."])
  var excludeResources = mutableListOf<String>()
  @Option(names = ["--exclude-renaming"], paramLabel = "REGEX", description = ["Prevent renaming matching classes; repeatable."])
  var excludeRenaming = mutableListOf<String>()
  @Option(names = ["--target-package"], paramLabel = "PACKAGE", description = ["Destination package; requires --flatten-packages."])
  var targetPackage: String? = null
  @Option(names = ["--flatten-packages"], description = ["Flatten renamed classes into --target-package."])
  var flattenPackages = false
  @Option(names = ["--control-flow"], description = ["Obfuscate branches and switches."])
  var controlFlow = false
  @Option(names = ["--control-flow-mode"], paramLabel = "MODE", description = ["Mode: inherit (default), none, light or dispatch. Explicit modes override --protection and legacy control-flow switches."])
  var controlFlowMode = ControlFlowMode.INHERIT
  @Option(names = ["--disable-feature"], paramLabel = "FEATURE=REGEX", converter = [FeatureExclusionConverter::class], description = ["Disable a feature for matching original methods; repeatable. Features: strings, numbers, control-flow, calls, integer-arithmetic, constant-fields."])
  var featureExclusions = mutableListOf<ProtectionRule>()
  @Option(names = ["--call-indirection"], description = ["Encrypt call linkage using constant invokedynamic sites; requires application roots."])
  var callIndirection = false
  @Option(names = ["--integer-arithmetic"], description = ["Rewrite bounded int/long addition, subtraction and XOR expressions."])
  var integerArithmetic = false
  @Option(names = ["--arithmetic-operations"], description = ["Maximum arithmetic rewrites per method (default: 16)."])
  var arithmeticOperations = 16
  @Option(names = ["--prelink-calls"], description = ["Generate an explicit bounded prelink API; requires --call-indirection."])
  var prelinkCalls = false
  @Option(names = ["--max-input-instructions"], paramLabel = "N", description = ["Skip a body pass above this input instruction count (default: unlimited)."])
  var maxInputInstructions: Int? = null
  @Option(names = ["--max-input-branches"], paramLabel = "N", description = ["Skip a body pass above this input branch count (default: unlimited)."])
  var maxInputBranches: Int? = null
  @Option(names = ["--max-added-instructions"], paramLabel = "N", description = ["Maximum cumulative added instructions per method (default: unlimited)."])
  var maxAddedInstructions: Int? = null
  @Option(names = ["--max-growth-percent"], description = ["Maximum cumulative instruction size relative to original (100 = unchanged)."])
  var maxGrowthPercent: Int? = null
  @Option(names = ["--max-output-code-bytes"], paramLabel = "N", description = ["Maximum estimated method code bytes, 1..65535 (default: 65535)."])
  var maxOutputCodeBytes = 65535
  @Option(names = ["--transformation-report"], paramLabel = "FILE", description = ["Write per-method decisions, reasons and structural costs as JSON."])
  var transformationReport: Path? = null
  @Option(names = ["--lightweight-control-flow"], description = ["Reorder direct-branch blocks without a state dispatcher; overrides dispatch mode."])
  var lightweightControlFlow = false
  @Option(names = ["--integrity-private-key"], paramLabel = "FILE", description = ["PKCS#8 RSA private key; requires a public key."])
  var integrityPrivateKey: Path? = null
  @Option(names = ["--integrity-public-key"], paramLabel = "FILE", description = ["X.509 RSA public key; requires a private key."])
  var integrityPublicKey: Path? = null
  @Option(names = ["--virtualize-method"], paramLabel = "REGEX", description = ["Select methods for virtualization; repeatable."])
  var virtualizeMethods = mutableListOf<String>()
  @Option(names = ["--exclude-virtualization"], paramLabel = "REGEX", description = ["Exclude methods from virtualization; repeatable."])
  var excludeVirtualization = mutableListOf<String>()
  @Option(names = ["--virtualization-mode"], paramLabel = "MODE", completionCandidates = VirtualizationModeCandidates::class, converter = [VirtualizationModeConverter::class], description = ["Selection mode: strict (default) or eligible."])
  var virtualizationMode = VirtualizationSelectionMode.STRICT
  @Option(names = ["--virtualization-analyze-only"], description = ["Analyze eligibility without writing output or mapping."])
  var virtualizationAnalyzeOnly = false
  @Option(names = ["--virtualization-report"], paramLabel = "FILE", description = ["Write a JSON virtualization eligibility report."])
  var virtualizationReport: Path? = null
  @Option(names = ["--virtualization-runtime-group-size"], paramLabel = "N", description = ["Maximum methods per runtime group (positive; default: 8)."])
  var virtualizationRuntimeGroupSize = 8
  @Option(names = ["--classpath"], paramLabel = "PATH", description = ["Dependency JAR or directory; repeatable."])
  var classpath = mutableListOf<Path>()
  @Option(names = ["--recompute-frames"], description = ["Recompute StackMap frames."])
  var recomputeFrames = false
  @Option(names = ["--no-string-encryption"], description = ["Disable string literal encryption."])
  var noStringEncryption = false
  @Option(names = ["--no-number-obfuscation"], description = ["Disable numeric literal obfuscation."])
  var noNumberObfuscation = false
  @Option(names = ["--no-string-constant-fields"], description = ["Disable extraction of string constant fields."])
  var noStringConstantFields = false
  @Option(names = ["--no-number-constant-fields"], description = ["Disable extraction of numeric constant fields."])
  var noNumberConstantFields = false
  @Option(names = ["--no-rename"], description = ["Disable class, method, and field renaming."])
  var noRename = false
  @Option(names = ["--no-rename-classes"], description = ["Disable class renaming."])
  var noRenameClasses = false
  @Option(names = ["--no-rename-methods"], description = ["Disable method renaming."])
  var noRenameMethods = false
  @Option(names = ["--no-rename-fields"], description = ["Disable field renaming."])
  var noRenameFields = false
  @Option(names = ["--strip-kotlin-metadata"], description = ["Strip Kotlin metadata throughout the JAR."])
  fun stripKotlinMetadata(enabled: Boolean) {
    stripMetadata = enabled
  }
  @Option(names = ["--preserve-kotlin-metadata"], description = ["Preserve Kotlin metadata and protect Kotlin class names."])
  fun preserveKotlinMetadata(enabled: Boolean) {
    if (enabled) {
      stripMetadata = false
      kotlinMetadataMode = KotlinMetadataMode.PRESERVE
    }
  }
  private var stripMetadata = false
  private var kotlinMetadataMode = KotlinMetadataMode.AUTO
  @Option(names = ["--application-package"], paramLabel = "PACKAGE", description = ["Additional application package root; repeatable."])
  var applicationPackages = mutableListOf<String>()
  @Option(names = ["--verify-output"], description = ["Validate emitted classes with ASM."])
  var verifyOutput = false
  @Option(names = ["--dry-run"], description = ["Transform in memory without writing output or mapping."])
  var dryRun = false
  @Option(names = ["--keep-debug-info"], description = ["Preserve source names, line numbers, and local variable debug data."])
  var keepDebugInfo = false
  @Option(names = ["--strip-generic-signatures"], description = ["Remove generic signature attributes."])
  var stripGenericSignatures = false
  @Option(names = ["--keep-method-parameters"], description = ["Preserve method parameter-name metadata."])
  var keepMethodParameters = false

  internal fun configuration(): ObfuscationConfig {
    require(virtualizationRuntimeGroupSize > 0) { "--virtualization-runtime-group-size must be positive" }
    return ObfuscationConfig(
      protectionLevel = protectionLevel,
      stripDebugInfo = !keepDebugInfo,
      stripKotlinMetadata = stripMetadata, stringEncryption = !noStringEncryption, numberObfuscation = !noNumberObfuscation,
      stringConstantFields = !noStringConstantFields, numberConstantFields = !noNumberConstantFields,
      kotlinMetadataMode = kotlinMetadataMode, applicationPackages = applicationPackages,
      renameClasses = !noRename && !noRenameClasses, renameMethods = !noRename && !noRenameMethods,
      renameFields = !noRename && !noRenameFields,
      seed = seed, platform = platform, platformOptions = platformOptions.associate { it.key to it.value }, keep = keep,
      verifyOutput = verifyOutput, dryRun = dryRun,
      stripGenericSignatures = stripGenericSignatures, stripMethodParameters = !keepMethodParameters,
      classpath = classpath, recomputeFrames = recomputeFrames,
      excludeClasses = excludeClasses, excludeResources = excludeResources, excludeRenaming = excludeRenaming,
      targetPackage = targetPackage, flattenPackages = flattenPackages, controlFlow = controlFlow,
      lightweightControlFlow = lightweightControlFlow, callIndirection = callIndirection,
      controlFlowMode = controlFlowMode, protectionRules = featureExclusions.toList(),
      integerArithmetic = integerArithmetic, maxArithmeticOperationsPerMethod = arithmeticOperations,
      prelinkCalls = prelinkCalls, transformationReport = transformationReport,
      transformationBudget = com.peco2282.obfaryn.api.protection.TransformationBudget(
        maxInputInstructions, maxInputBranches, maxAddedInstructions, maxGrowthPercent, maxOutputCodeBytes),
      virtualizeMethods = virtualizeMethods,
      excludeVirtualization = excludeVirtualization,
      integrityPrivateKey = integrityPrivateKey, integrityPublicKey = integrityPublicKey,
      virtualizationRuntimeGroupSize = virtualizationRuntimeGroupSize,
      virtualizationSelectionMode = virtualizationMode, virtualizationReport = virtualizationReport,
      virtualizationAnalyzeOnly = virtualizationAnalyzeOnly,
    )
  }

  override fun call(): Int {
    if (!::input.isInitialized) throw CommandLine.ParameterException(spec.commandLine(), "Missing required parameter: 'INPUT'. Use --help for usage.")
    val out = output ?: input.resolveSibling(input.fileName.toString().removeSuffix(".jar") + "-obfuscated.jar")
    val config = configuration()
    report?.let { summary ->
      val sensitive = listOfNotNull(input, out, mapping ?: out.resolveSibling(out.fileName.toString().removeSuffix(".jar") + ".mapping.txt"),
        config.transformationReport, config.virtualizationReport ?: if (config.protectionLevel == ProtectionLevel.STRONG)
          out.resolveSibling(out.fileName.toString().removeSuffix(".jar") + ".virtualization.json") else null,
        config.integrityPrivateKey, config.integrityPublicKey) + config.classpath
      if (sensitive.any { it.toAbsolutePath().normalize() == summary.toAbsolutePath().normalize() ||
          Files.exists(it) && Files.exists(summary) && Files.isSameFile(it, summary) }) {
        throw CommandLine.ParameterException(spec.commandLine(), "--report must use a different file from input, output, dependencies, keys and other reports")
      }
    }
    val result = Obfuscator(config).obfuscate(input, out, mapping)
    val writer = spec.commandLine().out
    writer.println("Loaded ${result.classesLoaded} classes, ${result.resourcesLoaded} resources")
    writer.println("Platform: ${result.platform}")
    writer.println("Strings encrypted: ${result.stringsEncrypted}")
    writer.println("Constants obfuscated: ${result.constantsObfuscated}")
    writer.println("Classes renamed: ${result.classesRenamed}")
    writer.println("Methods renamed: ${result.methodsRenamed}")
    writer.println("Fields renamed: ${result.fieldsRenamed}")
    writer.println("Branches obfuscated: ${result.branchesObfuscated}")
    writer.println("Methods virtualized: ${result.methodsVirtualized}")
    result.transformerReports.forEach { pass ->
      writer.println("Transformer ${pass.id}: ${pass.metrics.entries.joinToString { "${it.key}=${it.value}" }}")
    }
    if (virtualizationAnalyzeOnly) writer.println("Analyze only: no output or mapping written")
    else if (dryRun) writer.println("Dry run: no output or mapping written") else writer.println("Output: ${result.output}")
    result.mappingPreview?.takeIf { it.isNotBlank() }?.let { writer.println("Planned renames:\n$it") }
    result.mapping?.let { writer.println("Mapping: $it") }
    result.warnings.forEach { spec.commandLine().err.println("WARNING: $it") }
    report?.let { path ->
      path.parent?.let(Files::createDirectories)
      fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
      val json = """{
  "classesLoaded": ${result.classesLoaded},
  "resourcesLoaded": ${result.resourcesLoaded},
  "platform": ${quote(result.platform)},
  "stringsEncrypted": ${result.stringsEncrypted},
  "constantsObfuscated": ${result.constantsObfuscated},
  "classesRenamed": ${result.classesRenamed},
  "methodsRenamed": ${result.methodsRenamed},
  "fieldsRenamed": ${result.fieldsRenamed},
  "branchesObfuscated": ${result.branchesObfuscated},
  "methodsVirtualized": ${result.methodsVirtualized},
  "virtualizationAnalysis": ${result.virtualizationAnalysis?.toJson() ?: "null"},
  "transformerReports": ${result.transformerReports.joinToString(", ", "[", "]") { pass ->
    "{\"id\": ${quote(pass.id)}, \"metrics\": ${pass.metrics.entries.joinToString(", ", "{", "}") { (key, value) -> "${quote(key)}: $value" }}}"
  }},
  "dryRun": $dryRun,
  "warnings": ${result.warnings.joinToString(", ", "[", "]") { quote(it) }}
}
"""
      path.writeText(json)
    }
    writer.flush()
    return 0
  }
}

internal data class PlatformOption(val key: String, val value: String)
internal class FeatureExclusionConverter : CommandLine.ITypeConverter<ProtectionRule> {
  override fun convert(value: String): ProtectionRule {
    val feature = value.substringBefore('=')
    val pattern = value.substringAfter('=', "")
    if (pattern.isBlank()) throw CommandLine.TypeConversionException("--disable-feature requires FEATURE=REGEX with a non-empty regex")
    val key = feature.replace('-', '_').uppercase(Locale.ROOT)
    val selected = BodyFeature.values().firstOrNull { it.name == key }
      ?: throw CommandLine.TypeConversionException("Unknown feature '$feature'; use strings, numbers, control-flow, calls, integer-arithmetic or constant-fields")
    return try {
      ProtectionRule(pattern, mapOf(selected to false))
    } catch (error: IllegalArgumentException) {
      throw CommandLine.TypeConversionException("Invalid method regex: ${error.message}")
    }
  }
}

internal class PlatformOptionConverter : CommandLine.ITypeConverter<PlatformOption> {
  override fun convert(value: String): PlatformOption {
    if ('=' !in value || value.substringBefore('=').isBlank()) {
      throw CommandLine.TypeConversionException("Platform option must be key=value")
    }
    return PlatformOption(value.substringBefore('='), value.substringAfter('='))
  }
}

internal class PlatformCandidates : ArrayList<String>(listOf("auto") + Platforms.all().map { it.id })
internal class VirtualizationModeCandidates : ArrayList<String>(listOf("strict", "eligible"))
internal class VirtualizationModeConverter : CommandLine.ITypeConverter<VirtualizationSelectionMode> {
  override fun convert(value: String): VirtualizationSelectionMode = when (value.lowercase()) {
    "strict" -> VirtualizationSelectionMode.STRICT
    "eligible" -> VirtualizationSelectionMode.ELIGIBLE
    else -> throw CommandLine.TypeConversionException("Virtualization mode must be strict or eligible")
  }
}

@Command(name = "deobfuscate-stacktrace", mixinStandardHelpOptions = true, description = ["Restore names in a stacktrace using a mapping file."])
internal class DeobfuscateCommand : Callable<Int> {
  @Spec lateinit var spec: CommandSpec
  @Option(names = ["--mapping"], required = true, paramLabel = "FILE", description = ["Rename mapping file."])
  lateinit var mapping: Path
  @Parameters(index = "0", paramLabel = "STACKTRACE", description = ["Stacktrace text file."])
  lateinit var stacktrace: Path

  override fun call(): Int {
    spec.commandLine().out.print(Mapping.read(mapping).deobfuscateStacktrace(stacktrace.readText()))
    spec.commandLine().out.flush()
    return 0
  }
}

internal enum class Shell { bash, zsh }

@Command(name = "completion", mixinStandardHelpOptions = true, description = ["Print a completion script. Source it in bash or zsh (via bashcompinit)."])
internal class CompletionCommand : Callable<Int> {
  @Spec lateinit var spec: CommandSpec
  @Parameters(index = "0", paramLabel = "SHELL", description = ["Shell: \${COMPLETION-CANDIDATES}."])
  lateinit var shell: Shell

  override fun call(): Int {
    val writer = spec.commandLine().out
    if (shell == Shell.zsh) writer.println("autoload -Uz compinit bashcompinit\ncompinit\nbashcompinit")
    writer.print(AutoComplete.bash("obfaryn", spec.root().commandLine()))
    writer.flush()
    return 0
  }
}
