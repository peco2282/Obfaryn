package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.PlatformConfiguration
import com.peco2282.obfaryn.core.transformer.VirtualizationAnalysisReport
import com.peco2282.obfaryn.core.transformer.VirtualizationSelectionMode
import java.nio.file.Path

/**
 * Controls handling of the `kotlin.Metadata` annotation.
 *
 * [AUTO] strips metadata only from detected application packages, [PRESERVE] keeps it and
 * protects affected Kotlin class names, and [STRIP] removes it throughout the input JAR.
 * Obfaryn does not rewrite metadata after renaming.
 */
enum class KotlinMetadataMode { AUTO, PRESERVE, STRIP }

/**
 * Options for one [Obfuscator.obfuscate] invocation.
 *
 * Defaults enable metadata/debug cleanup and conservative literal and name transformations.
 * Regex lists use full matches against original JVM internal names, resource paths, or
 * `owner.method(descriptor)` identities. Keep rules use the syntax described in the README.
 *
 * @property stripDebugInfo Remove line numbers, source names, and local variable debug data.
 * @property stripKotlinMetadata Legacy switch to strip Kotlin metadata throughout the JAR;
 *   prefer [kotlinMetadataMode] for new callers.
 * @property kotlinMetadataMode Scope of Kotlin metadata removal and associated name protection.
 * @property applicationPackages Additional package roots for application-only transformations.
 * @property stringEncryption Encrypt eligible string literals.
 * @property numberObfuscation Transform eligible numeric constants.
 * @property renameClasses Enable conservative class renaming.
 * @property renameMethods Enable method-family and private/static method renaming.
 * @property renameFields Enable eligible field renaming.
 * @property controlFlow Apply lightweight branch and switch transformations.
 * @property seed Optional random seed; identical input, settings, seed, and tool version are reproducible.
 * @property platform Platform ID, or `auto` for detection.
 * @property keep Explicit class, package, method, and field keep rules.
 * @property reproducible Write deterministic JAR entry ordering and timestamps.
 * @property stripGenericSignatures Remove generic signature attributes.
 * @property stripMethodParameters Remove method parameter-name metadata.
 * @property verifyOutput Validate emitted classes with ASM.
 * @property dryRun Analyze and transform in memory without writing a JAR or mapping.
 * @property classpath External JARs or directories needed for hierarchy and frame resolution.
 * @property recomputeFrames Recalculate StackMap frames; unresolved required types cause failure.
 * @property platformConfiguration Additional keep and virtualization policy rules for this run.
 * @property excludeClasses Full-match class regexes suppressing body and name transformations.
 * @property excludeResources Full-match resource-path regexes suppressing resource transformations.
 * @property excludeRenaming Full-match class regexes suppressing name changes only.
 * @property targetPackage Destination for eligible classes when [flattenPackages] is enabled.
 * @property flattenPackages Move eligible renamed classes into [targetPackage].
 * @property virtualizeMethods Full-match method regexes selecting interpreter conversion.
 * @property excludeVirtualization Full-match method regexes taking priority over selection.
 * @property integrityPrivateKey PKCS#8 RSA private key; requires [integrityPublicKey].
 * @property integrityPublicKey X.509 RSA public key embedded in the result.
 * @property virtualizationRuntimeGroupSize Maximum methods per same-package runtime group; must be positive.
 * @property virtualizationMode Legacy selection mode; prefer [virtualizationSelectionMode].
 * @property virtualizationReport Optional JSON eligibility-report destination.
 * @property virtualizationSelectionMode Strict failure, eligible-only conversion, or analysis-only mode.
 * @property virtualizationAnalyzeOnly Produce analysis without writing transformed artifacts.
 */
data class ObfuscationConfig(
  val stripDebugInfo: Boolean = true,
  val stripKotlinMetadata: Boolean = false,
  val kotlinMetadataMode: KotlinMetadataMode = KotlinMetadataMode.AUTO,
  val applicationPackages: List<String> = emptyList(),
  val stringEncryption: Boolean = true,
  val numberObfuscation: Boolean = true,
  val renameClasses: Boolean = true,
  val renameMethods: Boolean = true,
  val renameFields: Boolean = true,
  val controlFlow: Boolean = false,
  val seed: Long? = null,
  val platform: String = "auto",
  val platformOptions: Map<String, String> = emptyMap(),
  val keep: List<String> = emptyList(),
  val reproducible: Boolean = true,
  val stripGenericSignatures: Boolean = false,
  val stripMethodParameters: Boolean = true,
  val verifyOutput: Boolean = false,
  val dryRun: Boolean = false,
  val classpath: List<Path> = emptyList(),
  val recomputeFrames: Boolean = false,
  val platformConfiguration: (PlatformConfiguration.() -> Unit)? = null,
  val excludeClasses: List<String> = emptyList(),
  val excludeResources: List<String> = emptyList(),
  val excludeRenaming: List<String> = emptyList(),
  val targetPackage: String? = null,
  val flattenPackages: Boolean = false,
  val virtualizeMethods: List<String> = emptyList(),
  val excludeVirtualization: List<String> = emptyList(),
  val integrityPrivateKey: Path? = null,
  val integrityPublicKey: Path? = null,
  val virtualizationRuntimeGroupSize: Int = 8,
  val virtualizationMode: VirtualizationSelectionMode = VirtualizationSelectionMode.STRICT,
  val virtualizationReport: Path? = null,
  val virtualizationSelectionMode: VirtualizationSelectionMode = virtualizationMode,
  val virtualizationAnalyzeOnly: Boolean = false,
)

/**
 * Summary returned by [Obfuscator.obfuscate].
 *
 * Counts describe the processed input and transformations. [output] is the requested destination,
 * even in dry-run or analysis-only mode, when no JAR is written. [mapping] is null if no mapping
 * was written; [mappingPreview] contains planned renames during a dry run.
 *
 * @property warnings Compatibility, skipped-transformation, and policy diagnostics.
 * @property virtualizationAnalysis Eligibility details, including rejected methods and reasons.
 */
data class ObfuscationResult(
  val classesLoaded: Int,
  val resourcesLoaded: Int,
  val platform: String,
  val stringsEncrypted: Int,
  val constantsObfuscated: Int,
  val classesRenamed: Int,
  val methodsRenamed: Int,
  val fieldsRenamed: Int,
  val output: Path,
  val mapping: Path?,
  val warnings: List<String> = emptyList(),
  val mappingPreview: String? = null,
  val branchesObfuscated: Int = 0,
  val methodsVirtualized: Int = 0,
  val virtualizationAnalysis: VirtualizationAnalysisReport? = null,
)
