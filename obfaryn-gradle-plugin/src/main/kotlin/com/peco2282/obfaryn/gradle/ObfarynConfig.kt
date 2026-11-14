package com.peco2282.obfaryn.gradle

import com.peco2282.obfaryn.core.KotlinMetadataMode
import com.peco2282.obfaryn.core.ObfuscationConfig
import com.peco2282.obfaryn.core.ProtectionLevel
import com.peco2282.obfaryn.core.transformer.VirtualizationSelectionMode
import org.gradle.api.Project
import java.nio.file.Path

/**
 * Builds the immutable [ObfuscationConfig] for one `obfaryn { }` extension.
 *
 * Shared by the single-artifact `obfuscateJar` task and the cross-artifact assembly task so a
 * project configures its obfuscation the same way in either path. [dependencyClasspath] is the
 * resolved classpath to hand the engine for hierarchy and frame resolution.
 */
internal fun ObfarynExtension.toObfuscationConfig(project: Project, dependencyClasspath: List<Path>): ObfuscationConfig =
  ObfuscationConfig(
    protectionLevel = ProtectionLevel.valueOf(protection.uppercase()),
    stripDebugInfo = stripDebugInfo,
    stripKotlinMetadata = stripKotlinMetadata,
    kotlinMetadataMode = KotlinMetadataMode.valueOf(kotlinMetadataMode.uppercase()),
    applicationPackages = applicationPackages,
    stripGenericSignatures = stripGenericSignatures,
    stripMethodParameters = stripMethodParameters,
    verifyOutput = verifyOutput,
    reproducible = reproducible,
    recomputeFrames = recomputeFrames,
    classpath = dependencyClasspath,
    stringEncryption = stringEncryption,
    numberObfuscation = numberObfuscation,
    stringConstantFields = string.includeConstantFields,
    numberConstantFields = number.includeConstantFields,
    stringEncryptionStrategy = string.strategy,
    numberStrategy = number.strategy,
    numberSelector = number.selector,
    stringRandom = string.random,
    numberRandom = number.random,
    renameClasses = rename.classes,
    renameMethods = rename.methods,
    renameFields = rename.fields,
    seed = seed,
    platform = platform,
    additionalPlatforms = additionalPlatforms,
    platformOptions = platformOptions,
    keep = keep,
    excludeClasses = excludeClasses,
    excludeResources = excludeResources,
    excludeRenaming = excludeRenaming,
    targetPackage = targetPackage,
    flattenPackages = flattenPackages,
    controlFlow = controlFlow,
    lightweightControlFlow = lightweightControlFlow,
    controlFlowMode = flowMode(controlFlowMode),
    callIndirection = callIndirection,
    integerArithmetic = integerArithmetic,
    maxArithmeticOperationsPerMethod = maxArithmeticOperationsPerMethod,
    prelinkCalls = prelinkCalls,
    transformationBudget = performance.budget(),
    protectionRules = protectionRules,
    transformationReport = transformationReport?.let(project::file)?.toPath(),
    virtualizeMethods = virtualization.methods,
    excludeVirtualization = virtualization.excludeMethods,
    virtualizationRuntimeGroupSize = virtualization.runtimeGroupSize,
    virtualizationSelectionMode = when (virtualization.mode) {
      "strict" -> VirtualizationSelectionMode.STRICT
      "eligible" -> VirtualizationSelectionMode.ELIGIBLE
      else -> error("Virtualization mode must be strict or eligible")
    },
    virtualizationAnalyzeOnly = virtualization.analyzeOnly,
    virtualizationReport = virtualization.report?.let { project.file(it).toPath() },
    integrityPrivateKey = integrityPrivateKey?.let { project.file(it).toPath() },
    integrityPublicKey = integrityPublicKey?.let { project.file(it).toPath() },
  )
