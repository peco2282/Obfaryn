package com.peco2282.obfaryn.gradle

import com.peco2282.obfaryn.core.ObfuscationConfig
import com.peco2282.obfaryn.core.KotlinMetadataMode
import com.peco2282.obfaryn.core.Obfuscator
import com.peco2282.obfaryn.core.transformer.VirtualizationSelectionMode
import com.peco2282.obfaryn.transformer.StringEncryptionStrategy
import com.peco2282.obfaryn.transformer.XorStringEncryptionStrategy
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.nio.file.Path
import java.util.Random


/**
 * An annotation used to mark experimental APIs that require explicit opt-in to utilize.
 *
 * Applying this annotation indicates that the associated API is not yet stable and may be subject
 * to changes or removal in future updates. Users must declare their opt-in explicitly by
 * annotating their code with the specified opt-in annotation.
 *
 * This annotation enforces a strict level of requirement, meaning that using the annotated APIs
 * without opting in will result in a compilation error.
 *
 * @see com.peco2282.obfaryn.annotations.Virtualize
 */
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message = "Pattern-based virtualization is experimental. Prefer @Virtualize on the target class or method."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class VirtualizeOptIn

/**
 * Configuration options for controlling renaming behavior during obfuscation.
 *
 * This class provides flags to enable or disable renaming for specific types of program elements,
 * such as classes, methods, and fields.
 *
 * @property classes Determines whether classes should be renamed during the obfuscation process.
 * @property methods Determines whether methods should be renamed during the obfuscation process.
 * @property fields Determines whether fields should be renamed during the obfuscation process.
 */
open class RenameOptions {
  var classes = true
  var methods = true
  var fields = true
}

/** String options; [enabled] shares its value with the legacy extension property. */
open class StringOptions internal constructor(private val extension: ObfarynExtension) {
  var enabled: Boolean
    get() = extension.stringEncryption
    set(value) { extension.stringEncryption = value }
  var includeConstantFields = true
  var strategy: StringEncryptionStrategy = XorStringEncryptionStrategy()
  /** Null uses the shared generator controlled by `obfaryn.seed`. */
  var random: Random? = null
}

/** Number options; null [random] uses the shared generator controlled by `obfaryn.seed`. */
open class NumberOptions internal constructor(private val extension: ObfarynExtension) {
  var enabled: Boolean
    get() = extension.numberObfuscation
    set(value) { extension.numberObfuscation = value }
  var includeConstantFields = true
  var random: Random? = null
}

/** Metadata options are aliases of the extension's existing flat properties. */
open class MetadataOptions internal constructor(private val extension: ObfarynExtension) {
  var stripDebugInfo: Boolean
    get() = extension.stripDebugInfo
    set(value) { extension.stripDebugInfo = value }
  var stripKotlinMetadata: Boolean
    get() = extension.stripKotlinMetadata
    set(value) { extension.stripKotlinMetadata = value }
  var kotlinMetadataMode: String
    get() = extension.kotlinMetadataMode
    set(value) { extension.kotlinMetadataMode = value }
  var stripGenericSignatures: Boolean
    get() = extension.stripGenericSignatures
    set(value) { extension.stripGenericSignatures = value }
  var stripMethodParameters: Boolean
    get() = extension.stripMethodParameters
    set(value) { extension.stripMethodParameters = value }
}

/** Output paths resolve relative to the project, not the shell's working directory. */
open class OutputOptions {
  /** Null uses `build/libs`, including a customized Gradle build directory. */
  var directory: String? = null
  /** Optional JAR name within [directory]; defaults to the input name plus outputSuffix. */
  var fileName: String? = null
  /** Explicit JAR path, taking precedence over [directory] and [fileName]. */
  var file: String? = null
  /** Explicit mapping path; null puts the mapping beside the output JAR. */
  var mapping: String? = null
}

/**
 * Advanced pattern-based virtualization settings; prefer source-level `@Virtualize`.
 *
 * [methods] and [excludeMethods] are full-match regexes over original
 * `owner.method(descriptor)` identities. Exclusions win over selections.
 * [mode] is `strict` or `eligible`; analysis-only mode writes a report but no JAR.
 */
open class VirtualizationOptions {
  var methods = listOf<String>()
  var excludeMethods = listOf<String>()
  var runtimeGroupSize = 8
  var mode = "strict"
  var analyzeOnly = false
  var report: String? = null
}

/**
 * Configuration class for the Obfaryn plugin, providing options for code obfuscation.
 *
 * This class allows users to customize various aspects of the obfuscation process, such as
 * renaming of program elements, stripping debug information, applying control flow transformations,
 * and virtualizing methods. The configuration options are exposed as properties that can
 * be modified in the build script.
 *
 * @property platform Defines the target platform for the obfuscation process. Defaults to "auto".
 * @property outputSuffix Specifies the suffix to be appended to the output file name. Defaults to "obfuscated".
 * @property stringEncryption Determines whether string literals should be encrypted during obfuscation.
 * @property numberObfuscation Determines whether numeric constants should be obfuscated.
 * @property stripDebugInfo Indicates whether debug information, such as line numbers and local variable tables,
 * should be removed from the bytecode.
 * @property stripKotlinMetadata Indicates whether Kotlin-specific metadata annotations should be removed.
 * @property kotlinMetadataMode Specifies the mode for handling Kotlin metadata. Defaults to "auto".
 * @property applicationPackages A list of application package prefixes used to identify classes to obfuscate.
 * @property stripGenericSignatures Indicates whether generic type signatures should be stripped from bytecode.
 * @property stripMethodParameters Indicates whether method parameter names should be removed.
 * @property verifyOutput Enables verification of the output bytecode to ensure correctness.
 * @property reproducible Ensures that the obfuscation process produces consistent outputs for identical inputs.
 * @property recomputeFrames Recomputes stack frames for the obfuscated bytecode if enabled.
 * @property classpath A list of additional paths to include in the classpath for the obfuscation process.
 * @property seed An optional seed value for initializing the random number generator used in obfuscation.
 * @property keep A list of patterns specifying elements to be excluded from obfuscation.
 * @property excludeClasses A list of class names to exclude from the obfuscation process.
 * @property excludeResources A list of resource files to exclude from the obfuscation process.
 * @property excludeRenaming A list of patterns specifying elements to be excluded from renaming.
 * @property targetPackage Specifies the target package for relocating renamed classes.
 * @property controlFlow Enables branch inversion and keyed switch dispatch.
 * @property flattenPackages Move eligible renamed classes into [targetPackage].
 * @property virtualizationRuntimeGroupSize The group size for runtime virtualization of methods.
 * @property integrityPrivateKey The path to the private key file used for generating integrity checks.
 * @property integrityPublicKey The path to the public key file used for verifying integrity checks.
 * @property rename Encapsulates options for renaming classes, methods, and fields.
 *
 * @constructor Creates a Gradle extension with default configuration values.
 * @see RenameOptions
 */
open class ObfarynExtension {
  var platform = "auto"
  var platformOptions = mapOf<String, String>()
  var outputSuffix = "obfuscated"

  var stringEncryption = true
  var numberObfuscation = true
  var stripDebugInfo = true
  var stripKotlinMetadata = false
  var kotlinMetadataMode = "auto"
  var applicationPackages = listOf<String>()
  var stripGenericSignatures = false
  var stripMethodParameters = true
  var verifyOutput = false
  var reproducible = true
  var recomputeFrames = false
  var classpath = listOf<String>()
  var seed: Long? = null
  var keep = listOf<String>()
  var excludeClasses = listOf<String>()
  var excludeResources = listOf<String>()
  var excludeRenaming = listOf<String>()
  var targetPackage: String? = null
  var flattenPackages = false
  var controlFlow = false
  var integrityPrivateKey: String? = null
  var integrityPublicKey: String? = null
  val rename = RenameOptions()
  val string = StringOptions(this)
  val number = NumberOptions(this)
  val metadata = MetadataOptions(this)
  val output = OutputOptions()
  internal val virtualization = VirtualizationOptions()
  fun rename(action: Action<RenameOptions>) = action.execute(rename)
  fun string(action: Action<StringOptions>) = action.execute(string)
  fun number(action: Action<NumberOptions>) = action.execute(number)
  fun metadata(action: Action<MetadataOptions>) = action.execute(metadata)
  fun output(action: Action<OutputOptions>) = action.execute(output)

  @VirtualizeOptIn
  fun virtualize(action: Action<VirtualizationOptions>) = action.execute(virtualization)
}

/** Registers the `obfaryn` extension and `obfuscateJar` task for Java projects. */
@Suppress("unused")
class ObfarynPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create("obfaryn", ObfarynExtension::class.java)
    project.plugins.withId("java") {
      project.afterEvaluate {
        val source = (project.tasks.findByName("shadowJar") ?: project.tasks.findByName("jar")) as? AbstractArchiveTask
          ?: return@afterEvaluate
        project.tasks.register("obfuscateJar") { task ->
          task.group = "build"
          task.description = "Obfuscates the output of ${source.name}"
          task.dependsOn(source)
          val outDir = project.provider {
            extension.output.directory?.let(project::file) ?: project.layout.buildDirectory.dir("libs").get().asFile
          }
          val outputFile = project.provider {
            extension.output.file?.let { path ->
              require(path.isNotBlank()) { "output.file must not be blank" }
              return@provider project.file(path)
            }
            val fileName = extension.output.fileName
              ?: source.archiveFile.get().asFile.name.removeSuffix(".jar") + "-${extension.outputSuffix}.jar"
            require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName && fileName.endsWith(".jar")) {
              "output.fileName must be a JAR file name; use output.file for a full path"
            }
            outDir.get().resolve(fileName)
          }
          val mappingFile = project.provider {
            extension.output.mapping?.let(project::file)
              ?: outputFile.get().parentFile.resolve(outputFile.get().name.removeSuffix(".jar") + ".mapping.txt")
          }
          task.inputs.file(source.archiveFile)
          task.outputs.files(outputFile, mappingFile)
          // Custom strategies and mutable Random instances cannot be fingerprinted reliably.
          task.outputs.upToDateWhen { false }
          task.doLast {
            val input = source.archiveFile.get().asFile.toPath()
            val output = outputFile.get().toPath()
            val mapping = mappingFile.get().toPath()
            val paths = listOf(input, output, mapping).map { it.toAbsolutePath().normalize() }
            require(paths.distinct().size == paths.size) { "Input JAR, output JAR and mapping must use different paths" }
            // Annotation-selected virtualization is only visible after the input JAR is read, so the
            // dependency classpath must be available even when virtualizeMethods is empty.
            val dependencyClasspath = (listOf("runtimeClasspath", "compileClasspath")
              .mapNotNull(project.configurations::findByName).flatMap { it.files }.map { it.toPath() } +
              extension.classpath.map(Path::of)).distinct().sortedBy { it.toString() }
            val config = ObfuscationConfig(
              stripDebugInfo = extension.stripDebugInfo,
              stripKotlinMetadata = extension.stripKotlinMetadata,
              kotlinMetadataMode = KotlinMetadataMode.valueOf(extension.kotlinMetadataMode.uppercase()),
              applicationPackages = extension.applicationPackages,
              stripGenericSignatures = extension.stripGenericSignatures,
              stripMethodParameters = extension.stripMethodParameters,
              verifyOutput = extension.verifyOutput,
              reproducible = extension.reproducible,
              recomputeFrames = extension.recomputeFrames,
              classpath = dependencyClasspath,
              stringEncryption = extension.stringEncryption,
              numberObfuscation = extension.numberObfuscation,
              stringConstantFields = extension.string.includeConstantFields,
              numberConstantFields = extension.number.includeConstantFields,
              stringEncryptionStrategy = extension.string.strategy,
              stringRandom = extension.string.random,
              numberRandom = extension.number.random,
              renameClasses = extension.rename.classes,
              renameMethods = extension.rename.methods,
              renameFields = extension.rename.fields,
              seed = extension.seed,
              platform = extension.platform,
              platformOptions = extension.platformOptions,
              keep = extension.keep,
              excludeClasses = extension.excludeClasses,
              excludeResources = extension.excludeResources,
              excludeRenaming = extension.excludeRenaming,
              targetPackage = extension.targetPackage,
              flattenPackages = extension.flattenPackages,
              controlFlow = extension.controlFlow,
              virtualizeMethods = extension.virtualization.methods,
              excludeVirtualization = extension.virtualization.excludeMethods,
              virtualizationRuntimeGroupSize = extension.virtualization.runtimeGroupSize,
              virtualizationSelectionMode = when (extension.virtualization.mode) {
                "strict" -> VirtualizationSelectionMode.STRICT
                "eligible" -> VirtualizationSelectionMode.ELIGIBLE
                else -> error("Virtualization mode must be strict or eligible")
              },
              virtualizationAnalyzeOnly = extension.virtualization.analyzeOnly,
              virtualizationReport = extension.virtualization.report?.let { project.file(it).toPath() },
              integrityPrivateKey = extension.integrityPrivateKey?.let { project.file(it).toPath() },
              integrityPublicKey = extension.integrityPublicKey?.let { project.file(it).toPath() },
            )
            val result = Obfuscator(config).obfuscate(input, output, mapping)
            project.logger.lifecycle("Obfaryn: ${result.classesLoaded} classes, ${result.stringsEncrypted} strings, ${result.constantsObfuscated} constants; output: $output")
            project.logger.lifecycle("Obfaryn: ${result.branchesObfuscated} branches, ${result.methodsVirtualized} virtualized methods")
            result.warnings.forEach { project.logger.warn("Obfaryn: $it") }
          }
        }
      }
    }
  }
}
