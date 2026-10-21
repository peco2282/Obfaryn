package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.PlatformConfiguration
import com.peco2282.obfaryn.api.transformer.TransformerReport
import com.peco2282.obfaryn.core.transformer.VirtualizationAnalysisReport
import com.peco2282.obfaryn.core.transformer.VirtualizationSelectionMode
import com.peco2282.obfaryn.api.transformer.StringEncryptionCodec
import com.peco2282.obfaryn.api.transformer.NumberObfuscationStrategy
import com.peco2282.obfaryn.api.transformer.NumberObfuscationSelector
import com.peco2282.obfaryn.transformer.XorStringEncryptionCodec
import com.peco2282.obfaryn.transformer.DefaultNumberObfuscationStrategy
import com.peco2282.obfaryn.transformer.DefaultNumberObfuscationSelector
import java.nio.file.Path
import java.util.Random

/**
 * Controls handling of the `kotlin.Metadata` annotation.
 *
 * [AUTO] and [PRESERVE] retain metadata and safely rewrite class names, types, JVM signatures,
 * and Kotlin module maps during renaming or relocation. Kotlin member names remain protected.
 * [STRIP] requests removal throughout the input JAR; exclusions and annotation policies can
 * still prevent removal. Unsupported metadata fails before output.
 *
 * Compatibility follows kotlin-metadata-jvm 2.4.21 readStrict for readable formats from 1.1
 * onward; formats older than 1.4 are upgraded to 1.4 when written. Unknown formats and opaque
 * plugin payloads are rejected. This does not imply testing every Kotlin compiler version.
 */
enum class KotlinMetadataMode { AUTO, PRESERVE, STRIP }

/**
 * Options for one [Obfuscator.obfuscate] invocation.
 *
 * Defaults enable metadata/debug cleanup and conservative literal and name transformations.
 * Regex lists use full matches against original JVM internal names, resource paths, or
 * `owner.method(descriptor)` identities. Keep rules use the syntax described in the README.
 *
 * Application roots combine entrypoint packages with [applicationPackages]. When roots are known
 * and global Kotlin metadata stripping is disabled, classes outside those roots are kept from
 * renaming. [KotlinMetadataMode.AUTO] retains application metadata, as does
 * [KotlinMetadataMode.PRESERVE]. [stripKotlinMetadata] overrides the mode.
 * Package flattening requires class renaming and a non-null, valid [targetPackage]; specifying a
 * target without flattening is rejected. These constraints are checked when obfuscation starts.
 *
 * @property stripDebugInfo Remove line numbers, source names, and local variable debug data.
 * @property stripKotlinMetadata Legacy switch to strip Kotlin metadata throughout the JAR;
 *   prefer [kotlinMetadataMode] for new callers.
 * @property kotlinMetadataMode Kotlin metadata retention or removal policy.
 * @property applicationPackages Additional package roots for application-only transformations.
 * @property stringEncryption Encrypt eligible string literals.
 * @property numberObfuscation Transform eligible numeric constants.
 * @property renameClasses Enable conservative class renaming.
 * @property renameMethods Enable method-family and private/static method renaming.
 * @property renameFields Enable eligible field renaming.
 * @property controlFlow Apply lightweight branch and switch transformations.
 * @property seed Optional random seed; identical input, settings, seed, and tool version are reproducible.
 * @property platform Platform ID, or `auto` for detection.
 * @property platformOptions String options passed to platform contributions, configuration, and entrypoint discovery.
 * @property keep Explicit class, package, method, and field keep rules.
 * @property reproducible Write deterministic JAR entry ordering and timestamps.
 * @property stripGenericSignatures Remove generic signature attributes.
 * @property stripMethodParameters Remove method parameter-name metadata.
 * @property verifyOutput Validate emitted classes with ASM.
 * @property dryRun Analyze and transform in memory without writing a JAR or mapping.
 * @property classpath External JARs or directories needed for hierarchy and frame resolution.
 * @property recomputeFrames Recalculate StackMap frames; also forced by control flow or selected virtualization.
 *   Unresolved required types cause failure during output generation.
 * @property platformConfiguration Additional keep and virtualization policy rules for this run.
 * @property excludeClasses Full-match class regexes hiding classes from ordinary passes and keeping
 *   their names and members; the renaming pass still rewrites references inside these classes.
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
 * @property stringConstantFields Extract eligible string constant fields before encryption.
 * @property numberConstantFields Extract eligible numeric constant fields before obfuscation.
 * @property stringEncryptionStrategy Paired string encryption and runtime decoder generation.
 * @property numberStrategy Typed numeric expression generation.
 * @property numberSelector Numeric target selection after body policy checks.
 * @property stringRandom Optional string-specific generator; defaults to the run's seeded generator.
 * @property numberRandom Optional number-specific generator; defaults to the run's seeded generator.
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
  val stringConstantFields: Boolean = true,
  val numberConstantFields: Boolean = true,
  val stringEncryptionStrategy: StringEncryptionCodec = XorStringEncryptionCodec(),
  val numberStrategy: NumberObfuscationStrategy = DefaultNumberObfuscationStrategy(),
  val numberSelector: NumberObfuscationSelector = DefaultNumberObfuscationSelector(),
  val stringRandom: Random? = null,
  val numberRandom: Random? = null,
) {
  companion object {
    /** Keeps runtime body transformations disabled; callers can opt in with copy(). */
    @JvmStatic
    fun lightweight(): ObfuscationConfig = ObfuscationConfig(
      stringEncryption = false, numberObfuscation = false,
    )
  }
}

/**
 * Summary returned by [Obfuscator.obfuscate].
 *
 * Counts describe the processed input and transformations. [output] is the requested destination,
 * even in dry-run or analysis-only mode, when no JAR is written. [mapping] is null if no mapping
 * was written; [mappingPreview] contains planned renames during a dry run.
 *
 * @property warnings Compatibility, skipped-transformation, and policy diagnostics.
 * @property virtualizationAnalysis Eligibility details, including rejected methods and reasons.
 * @property classesLoaded Class count immediately after reading the input, before platform contributions.
 * @property resourcesLoaded Resource count immediately after reading the input, including versioned classes.
 * @property transformerReports Reports from executed passes in execution order; absent in analysis-only mode.
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
  val transformerReports: List<TransformerReport> = emptyList(),
)
