package com.peco2282.obfaryn.cli

import com.peco2282.obfaryn.core.ObfuscationConfig
import com.peco2282.obfaryn.core.KotlinMetadataMode
import com.peco2282.obfaryn.core.Obfuscator
import com.peco2282.obfaryn.core.mapping.Mapping
import com.peco2282.obfaryn.core.transformer.VirtualizationSelectionMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  try {
    require(args.isNotEmpty() && args[0] !in listOf("--help", "-h")) { usage() }
    if (args[0] == "deobfuscate-stacktrace") {
      require(args.size == 4 && args[1] == "--mapping") { "Usage: obfaryn deobfuscate-stacktrace --mapping mapping.txt stacktrace.txt" }
      print(Mapping.read(Paths.get(args[2])).deobfuscateStacktrace(Paths.get(args[3]).readText()))
      return
    }
    val input = Paths.get(args[0])
    var output: Path? = null
    var mapping: Path? = null
    var platform = "auto"
    var seed: Long? = null
    var strings = true
    var numbers = true
    var rename = true
    var stripKotlinMetadata = false
    var kotlinMetadataMode = KotlinMetadataMode.AUTO
    val applicationPackages = mutableListOf<String>()
    var verifyOutput = false
    var dryRun = false
    var stripGenericSignatures = false
    var stripMethodParameters = true
    var recomputeFrames = false
    val classpath = mutableListOf<Path>()
    var report: Path? = null
    val keep = mutableListOf<String>()
    val excludeClasses = mutableListOf<String>()
    val excludeResources = mutableListOf<String>()
    val excludeRenaming = mutableListOf<String>()
    val virtualizeMethods = mutableListOf<String>()
    val excludeVirtualization = mutableListOf<String>()
    var virtualizationRuntimeGroupSize = 8
    var virtualizationMode = VirtualizationSelectionMode.STRICT
    var virtualizationAnalyzeOnly = false
    var virtualizationReport: Path? = null
    var targetPackage: String? = null
    var flattenPackages = false
    var controlFlow = false
    var integrityPrivateKey: Path? = null
    var integrityPublicKey: Path? = null
    var i = 1
    while (i < args.size) {
      when (val arg = args[i++]) {
        "-o", "--output" -> output = Paths.get(args.getOrElse(i++) { error("Missing output path") })
        "--mapping" -> mapping = Paths.get(args.getOrElse(i++) { error("Missing mapping path") })
        "--report" -> report = Paths.get(args.getOrElse(i++) { error("Missing report path") })
        "--platform" -> platform = args.getOrElse(i++) { error("Missing platform") }
        "--seed" -> seed = args.getOrElse(i++) { error("Missing seed") }.toLong()
        "--keep" -> keep += args.getOrElse(i++) { error("Missing keep rule") }
        "--exclude-class" -> excludeClasses += args.getOrElse(i++) { error("Missing class regex") }
        "--exclude-resource" -> excludeResources += args.getOrElse(i++) { error("Missing resource regex") }
        "--exclude-renaming" -> excludeRenaming += args.getOrElse(i++) { error("Missing renaming regex") }
        "--target-package" -> targetPackage = args.getOrElse(i++) { error("Missing target package") }
        "--flatten-packages" -> flattenPackages = true
        "--control-flow" -> controlFlow = true
        "--integrity-private-key" -> integrityPrivateKey = Paths.get(args.getOrElse(i++) { error("Missing private key path") })
        "--integrity-public-key" -> integrityPublicKey = Paths.get(args.getOrElse(i++) { error("Missing public key path") })
        "--virtualize-method" -> virtualizeMethods += args.getOrElse(i++) { error("Missing method regex") }
        "--exclude-virtualization" -> excludeVirtualization += args.getOrElse(i++) { error("Missing virtualization exclusion regex") }
        "--virtualization-mode" -> virtualizationMode = when (args.getOrElse(i++) { error("Missing virtualization mode") }) {
          "strict" -> VirtualizationSelectionMode.STRICT
          "eligible" -> VirtualizationSelectionMode.ELIGIBLE
          else -> error("Virtualization mode must be strict or eligible")
        }
        "--virtualization-analyze-only" -> virtualizationAnalyzeOnly = true
        "--virtualization-report" -> virtualizationReport = Paths.get(args.getOrElse(i++) { error("Missing virtualization report path") })
        "--virtualization-runtime-group-size" -> virtualizationRuntimeGroupSize = args.getOrElse(i++) { error("Missing runtime group size") }.toInt()
        "--classpath" -> classpath.add(Paths.get(args.getOrElse(i++) { error("Missing classpath path") }))
        "--recompute-frames" -> recomputeFrames = true
        "--no-string-encryption" -> strings = false
        "--no-number-obfuscation" -> numbers = false
        "--no-rename" -> rename = false
        "--strip-kotlin-metadata" -> stripKotlinMetadata = true
        "--preserve-kotlin-metadata" -> { stripKotlinMetadata = false; kotlinMetadataMode = KotlinMetadataMode.PRESERVE }
        "--application-package" -> applicationPackages.add(args.getOrElse(i++) { error("Missing application package") })
        "--verify-output" -> verifyOutput = true
        "--dry-run" -> dryRun = true
        "--strip-generic-signatures" -> stripGenericSignatures = true
        "--keep-method-parameters" -> stripMethodParameters = false
        else -> error("Unknown argument: $arg\n${usage()}")
      }
    }
    val out = output ?: if (dryRun || virtualizationAnalyzeOnly) input.resolveSibling(input.fileName.toString().removeSuffix(".jar") + "-obfuscated.jar")
      else error("Output path required\n${usage()}")
    val result = Obfuscator(ObfuscationConfig(
      stripKotlinMetadata = stripKotlinMetadata, stringEncryption = strings, numberObfuscation = numbers,
      kotlinMetadataMode = kotlinMetadataMode, applicationPackages = applicationPackages,
      renameClasses = rename, renameMethods = rename, renameFields = rename,
      seed = seed, platform = platform, keep = keep,
      verifyOutput = verifyOutput, dryRun = dryRun,
      stripGenericSignatures = stripGenericSignatures, stripMethodParameters = stripMethodParameters,
      classpath = classpath, recomputeFrames = recomputeFrames,
      excludeClasses = excludeClasses, excludeResources = excludeResources, excludeRenaming = excludeRenaming,
      targetPackage = targetPackage, flattenPackages = flattenPackages, controlFlow = controlFlow,
      virtualizeMethods = virtualizeMethods,
      excludeVirtualization = excludeVirtualization,
      integrityPrivateKey = integrityPrivateKey, integrityPublicKey = integrityPublicKey,
      virtualizationRuntimeGroupSize = virtualizationRuntimeGroupSize,
      virtualizationSelectionMode = virtualizationMode, virtualizationReport = virtualizationReport,
      virtualizationAnalyzeOnly = virtualizationAnalyzeOnly,
    )).obfuscate(input, out, mapping)
    println("Loaded ${result.classesLoaded} classes, ${result.resourcesLoaded} resources")
    println("Platform: ${result.platform}")
    println("Strings encrypted: ${result.stringsEncrypted}")
    println("Constants obfuscated: ${result.constantsObfuscated}")
    println("Classes renamed: ${result.classesRenamed}")
    println("Methods renamed: ${result.methodsRenamed}")
    println("Fields renamed: ${result.fieldsRenamed}")
    println("Branches obfuscated: ${result.branchesObfuscated}")
    println("Methods virtualized: ${result.methodsVirtualized}")
    if (virtualizationAnalyzeOnly) println("Analyze only: no output or mapping written")
    else if (dryRun) println("Dry run: no output or mapping written") else println("Output: ${result.output}")
    result.mappingPreview?.takeIf { it.isNotBlank() }?.let { println("Planned renames:\n$it") }
    result.mapping?.let { println("Mapping: $it") }
    result.warnings.forEach { System.err.println("WARNING: $it") }
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
  "dryRun": $dryRun,
  "warnings": ${result.warnings.joinToString(", ", "[", "]") { quote(it) }}
}
"""
      path.writeText(json)
    }
  } catch (e: Exception) {
    System.err.println(e.message)
    exitProcess(2)
  }
}

private fun usage() = "Usage: obfaryn input.jar -o output.jar [--virtualize-method regex] [--exclude-virtualization regex] [--virtualization-mode strict|eligible] [--virtualization-analyze-only] [--virtualization-report path] [--virtualization-runtime-group-size N] [--target-package package --flatten-packages] [--platform generic|paper] [--seed N] [--mapping path] [--report path] [--classpath dependency.jar] [--recompute-frames] [--application-package package] [--preserve-kotlin-metadata|--strip-kotlin-metadata] [--keep class|package.**|class#method(desc)|class#field] [--verify-output] [--dry-run] [--strip-generic-signatures] [--keep-method-parameters] [--no-string-encryption] [--no-number-obfuscation] [--no-rename]\n       obfaryn deobfuscate-stacktrace --mapping mapping.txt stacktrace.txt"
