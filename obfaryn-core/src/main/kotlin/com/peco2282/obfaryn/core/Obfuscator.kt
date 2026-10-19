package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.MapPlatformOptions
import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.api.transformer.PipelineConfigurer
import com.peco2282.obfaryn.api.transformer.TransformerReport
import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy
import com.peco2282.obfaryn.core.integrity.IntegrityProtection
import com.peco2282.obfaryn.core.jar.JarReader
import com.peco2282.obfaryn.core.jar.JarWriter
import com.peco2282.obfaryn.core.keep.KeepRules
import com.peco2282.obfaryn.core.platform.Platforms
import com.peco2282.obfaryn.core.platform.PlatformExtensionApplier
import com.peco2282.obfaryn.core.rename.RenameTransformer
import com.peco2282.obfaryn.core.transformer.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.*
import kotlin.io.path.writeText

/**
 * Runs the JAR transformation pipeline with one immutable [config].
 *
 * The pipeline resolves platform and keep policies, analyzes virtualization eligibility,
 * transforms classes in dependency-aware [Transformer.order] order, writes and optionally seals
 * the output, then writes the mapping. Each call reads a fresh archive context.
 *
 * [configurePipeline] edits the selected passes before [pipelineConfigurers] are applied in list
 * order. Disabling a pass does not disable archive loading, platform setup, or output processing.
 * Platform contributions are applied before annotation validation and keep-rule configuration;
 * the configured [ObfuscationConfig.platformConfiguration] callback runs after platform configuration.
 */
class Obfuscator(
  private val config: ObfuscationConfig = ObfuscationConfig(),
  private val jarReader: JarReader = JarReader(),
  private val jarWriter: JarWriter = JarWriter(),
  private val configurePipeline: (TransformerPipeline.() -> Unit)? = null,
  private val pipelineConfigurers: List<PipelineConfigurer> = emptyList(),
) {
  /**
   * Uses a custom transformer pipeline instead of the built-in transformation passes.
   * Platform selection, keep-policy setup, JAR output, and reporting still use default settings.
   */
  constructor(
    transformers: List<Transformer>,
    jarReader: JarReader = JarReader(),
    jarWriter: JarWriter = JarWriter()
  ) : this(
    ObfuscationConfig(), jarReader, jarWriter,
  ) {
    customTransformers = transformers
  }

  private var customTransformers: List<Transformer>? = null

  /**
   * Obfuscates [input] into [output] and optionally writes a mapping to [mapping].
   *
   * Input and output must be different paths. Invalid regexes, unsupported selected methods in
   * strict mode, unsafe multi-release references, and missing required keys fail before writing
   * the output. With dry-run or analysis-only settings, the result is returned without a JAR or
   * mapping. A virtualization report may still be written when configured.
   *
   * A dry run still executes transformations and integrity injection in memory; analysis-only
   * mode returns before those steps and before pipeline configuration callbacks. Loaded counts
   * exclude classes and resources subsequently contributed by the platform.
   *
   * If [mapping] is omitted, a sibling `<output-name>.mapping.txt` is written only when the
   * built-in renamer ran and produced changes. An explicit mapping destination is written even
   * when its contents are empty. Output, integrity sealing, and mapping writes are not a single
   * atomic operation: a failure can leave a partial JAR or a JAR without its mapping.
   *
   * @return Counts, diagnostics, paths, and optional virtualization analysis.
   * @throws IllegalArgumentException If path, configuration, or policy validation fails.
   * @throws IllegalStateException If a transformation pass fails, with its ID and original cause.
   */
  fun obfuscate(input: Path, output: Path, mapping: Path? = null): ObfuscationResult {
    val classExclusions = config.excludeClasses.map(::Regex)
    val resourceExclusions = config.excludeResources.map(::Regex)
    val renameExclusions = config.excludeRenaming.map(::Regex)
    config.virtualizeMethods.forEach(::Regex)
    require(
      !config.flattenPackages || (config.targetPackage != null && config.renameClasses &&
          Regex("[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)*").matches(config.targetPackage.replace('.', '/')))
    ) {
      "flattenPackages requires a valid targetPackage and renameClasses = true"
    }
    require(config.targetPackage == null || config.flattenPackages) {
      "targetPackage is only used with flattenPackages = true"
    }
    require(
      input.toAbsolutePath().normalize() != output.toAbsolutePath().normalize()
    ) { "Input and output paths must be different." }
    require(Files.isRegularFile(input)) { "Input JAR does not exist: $input" }
    val context = jarReader.read(input)
    val classesLoaded = context.classes.size
    val resourcesLoaded = context.resources.size
    val platform = Platforms.select(config.platform, context)
    val platformOptions = MapPlatformOptions(config.platformOptions)
    val appliedPlatformExtensions = PlatformExtensionApplier.apply(platform, context, platformOptions)
    ObfuscationAnnotationPolicy.validate(context.classes.values)
    val keep = KeepRules()
    config.keep.forEach(keep::add)
    platform.configure(context, platformOptions, keep)
    config.platformConfiguration?.invoke(keep)
    val annotatedNoObfuscationClasses = context.classes.values
      .filter(ObfuscationAnnotationPolicy::noObfuscation).mapTo(linkedSetOf()) { it.name }
    val excludedClasses = context.classes.keys.filter { name -> classExclusions.any { it.matches(name) } }.toSet() +
      annotatedNoObfuscationClasses
    val excludedResources =
      context.resources.keys.filter { name -> resourceExclusions.any { it.matches(name) } }.toSet()
    excludedClasses.forEach(keep::keepClass)
    for (owner in context.classes.values) {
      owner.methods.filter(ObfuscationAnnotationPolicy::noObfuscation)
        .forEach { keep.keepMethod(owner.name, it.name, it.desc) }
      owner.fields.filter(ObfuscationAnnotationPolicy::noObfuscation)
        .forEach { keep.keepField(owner.name, it.name) }
    }
    context.classes.keys.filter { name -> renameExclusions.any { it.matches(name) } }.forEach(keep::keepClass)
    excludedResources.forEach(keep::keepResource)
    val platformVirtualizationExclusions = keep.virtualizationExclusions()
    val virtualizationAnalysis = VirtualizationAnalyzer(
      config.virtualizeMethods, config.excludeVirtualization, platformVirtualizationExclusions)
      .analyze(context.classes.values.filter { it.name !in excludedClasses })
    val virtualizationRequested = virtualizationAnalysis.selected > 0
    config.virtualizationReport?.let { report ->
      require(listOfNotNull(input, output, mapping).none { it.toAbsolutePath().normalize() == report.toAbsolutePath().normalize() }) {
        "Virtualization report must not overwrite the input, output, or mapping"
      }
    }
    config.virtualizationReport?.let(virtualizationAnalysis::write)
    val analyzeOnly = config.virtualizationAnalyzeOnly || config.virtualizationSelectionMode == VirtualizationSelectionMode.ANALYZE_ONLY
    val virtualizationWarnings = virtualizationAnalysis.requireEligible(
      if (analyzeOnly) VirtualizationSelectionMode.ANALYZE_ONLY else config.virtualizationSelectionMode)
    if (analyzeOnly) {
      appliedPlatformExtensions.cleanup()
      return ObfuscationResult(classesLoaded, resourcesLoaded, platform.id, 0, 0, 0, 0, 0,
        output, null, virtualizationWarnings, virtualizationAnalysis = virtualizationAnalysis)
    }
    require((config.integrityPrivateKey == null) == (config.integrityPublicKey == null)) {
      "Both integrityPrivateKey and integrityPublicKey are required"
    }
    val integrity = config.integrityPrivateKey?.let {
      IntegrityProtection(it, config.integrityPublicKey!!)
    }
    val entrypoints = platform.entrypoints(context, platformOptions).toMutableSet()
    context.resources["META-INF/MANIFEST.MF"]?.let { entry ->
      val manifest = java.util.jar.Manifest(entry.data.inputStream())
      for (name in listOf("Main-Class", "Premain-Class", "Agent-Class", "Launcher-Agent-Class")) {
        manifest.mainAttributes.getValue(name)?.let {
          keep.keepClassNameAndPublicMembers(it)
          entrypoints += it.replace('.', '/')
        }
      }
    }
    for ((path, resource) in context.resources) {
      if (path.startsWith("META-INF/services/")) {
        keep.keepClassNameAndPublicMembers(path.removePrefix("META-INF/services/"))
        resource.data.toString(Charsets.UTF_8).lineSequence().map { it.substringBefore('#').trim() }
          .filter { it.isNotEmpty() }.forEach(keep::keepClassNameAndPublicMembers)
      }
    }
    val warnings = ReflectionScanner.scan(context, keep).toMutableList()
    if (virtualizationRequested) warnings += virtualizationWarnings
    val applicationRoots = (entrypoints.mapNotNull { it.substringBeforeLast('/', "").takeIf(String::isNotEmpty) } +
        config.applicationPackages.map { it.replace('.', '/').trimEnd('/') }).filter(String::isNotEmpty).distinct()
    val stripAllMetadata = config.stripKotlinMetadata || config.kotlinMetadataMode == KotlinMetadataMode.STRIP
    val versionedClasses = context.resources.filterKeys { Regex("META-INF/versions/[0-9]+/.+\\.class").matches(it) }
    if (versionedClasses.isNotEmpty()) {
      require(applicationRoots.isNotEmpty() && !stripAllMetadata) {
        "Multi-Release JAR requires an application package root and AUTO/PRESERVE Kotlin Metadata mode"
      }
      for ((path, resource) in versionedClasses) {
        val name = org.objectweb.asm.ClassReader(resource.data).className
        val raw = resource.data.toString(Charsets.ISO_8859_1)
        require(applicationRoots.none { name.startsWith("$it/") || raw.contains("$it/") }) {
          "Versioned class $path may refer to renamed application classes; refusing unsafe transformation"
        }
      }
      warnings += "Preserved ${versionedClasses.size} Multi-Release class entries without modification"
    }
    if (applicationRoots.isNotEmpty() && !stripAllMetadata) {
      for (node in context.classes.values) {
        if (applicationRoots.none { node.name.startsWith("$it/") }) keep.keepClass(node.name)
      }
    }
    val metadataClasses = context.classes.values.filter(keep::kotlinClass).map { it.name }
    val autoStrip = if (config.kotlinMetadataMode == KotlinMetadataMode.AUTO && !config.stripKotlinMetadata)
      metadataClasses.filter { name -> applicationRoots.any { name.startsWith("$it/") } }.toSet() else emptySet()
    if (stripAllMetadata && metadataClasses.isNotEmpty()) {
      warnings += "STRIP kotlin.Metadata on ${metadataClasses.size} classes may break Kotlin reflection and metadata-dependent libraries such as kotlinx.serialization"
    } else if (autoStrip.isNotEmpty()) {
      warnings += "AUTO stripped kotlin.Metadata on ${autoStrip.size} application classes (${applicationRoots.joinToString()}); Kotlin reflection and metadata-dependent libraries may break"
    }
    val signatures = context.resources.keys.filter { Regex("(?i)^META-INF/[^/]+\\.(SF|RSA|DSA|EC)$").matches(it) }
    if (signatures.isNotEmpty()) warnings += "Removed invalid JAR signatures: ${signatures.joinToString()}"
    val random = Random(config.seed ?: SecureRandom().nextLong())
    val strings = StringEncryptionTransformer(random)
    val numbers = NumberObfuscationTransformer(random)
    val constantFields =
      ConstantFieldTransformer(applicationRoots, config.stringEncryption, config.numberObfuscation, keep)
    val rename = RenameTransformer(config, keep)
    val flow = ControlFlowTransformer(random)
    val virtualization = VirtualizationTransformer(
      config.virtualizeMethods, random, config.virtualizationRuntimeGroupSize, config.virtualizationSelectionMode,
      config.classpath + listOf(input), config.excludeVirtualization, platformVirtualizationExclusions
    )
    val pipeline = customTransformers ?: listOfNotNull(
      virtualization.takeIf { virtualizationRequested },
      MetadataStripTransformer(
        stripDebugInfo = config.stripDebugInfo, stripKotlinMetadata = stripAllMetadata,
        kotlinMetadataClasses = autoStrip, stripGenericSignatures = config.stripGenericSignatures,
        stripMethodParameters = config.stripMethodParameters
      )
        .takeIf { config.stripDebugInfo || stripAllMetadata || autoStrip.isNotEmpty() || config.stripGenericSignatures || config.stripMethodParameters },
      constantFields.takeIf { applicationRoots.isNotEmpty() && (config.stringEncryption || config.numberObfuscation) },
      strings.takeIf { config.stringEncryption },
      numbers.takeIf { config.numberObfuscation },
      flow.takeIf { config.controlFlow },
      rename.takeIf { config.renameClasses || config.renameMethods || config.renameFields },
    )
    val orderedPipeline = TransformerPipeline(emptyList()).apply {
      pipeline.forEach(::add)
      configurePipeline?.invoke(this)
      pipelineConfigurers.forEach { it.configure(this) }
    }.ordered()
    val transformerReports = mutableListOf<TransformerReport>()
    for (transformer in orderedPipeline) {
      try {
        transformer.transform(
          if (transformer === rename) context else
            FilteredContext(context, excludedClasses, excludedResources)
        )
        val report = transformer.report()
        val count = when (transformer) {
          strings -> strings.count
          numbers -> numbers.count
          constantFields -> constantFields.count
          flow -> flow.count
          virtualization -> virtualization.count
          rename -> rename.classMapping.size + rename.methodMapping.size + rename.fieldMapping.size
          else -> null
        }
        transformerReports += if (count == null) report else report.copy(metrics = report.metrics + ("transformed" to count.toLong()))
      } catch (cause: Exception) {
        throw IllegalStateException("Failed transforming with ${transformer.id}", cause)
      }
    }
    warnings += transformerReports.flatMap { it.warnings }
    warnings += rename.warnings
    integrity?.inject(context, excludedClasses, random)
    if (virtualizationRequested && virtualization.count == 0)
      warnings += if (virtualizationAnalysis.selected == 0) "No methods virtualized: selectors matched no non-excluded methods"
        else "No methods virtualized: selectors matched ${virtualizationAnalysis.selected} methods, but none were eligible"
    appliedPlatformExtensions.cleanup()
    if (!config.dryRun) jarWriter.write(
      context, output, config.reproducible, config.verifyOutput,
      config.recomputeFrames || config.controlFlow || virtualizationRequested, config.classpath
    )
    if (!config.dryRun) integrity?.seal(output, config.reproducible)
    val renameExecuted = orderedPipeline.any { it === rename }
    val mappingOutput = if (config.dryRun) null else mapping
      ?: if (renameExecuted && (rename.classMapping.isNotEmpty() || rename.methodMapping.isNotEmpty() || rename.fieldMapping.isNotEmpty()))
        output.resolveSibling(output.fileName.toString().removeSuffix(".jar") + ".mapping.txt") else null
    if (mappingOutput != null) {
      mappingOutput.parent?.let(Files::createDirectories)
      mappingOutput.writeText(rename.mappingText())
    }
    return ObfuscationResult(
      classesLoaded, resourcesLoaded, platform.id, strings.count, numbers.count,
      rename.classMapping.size, rename.methodMapping.size, rename.fieldMapping.size, output, mappingOutput, warnings,
      if (config.dryRun) rename.mappingText() else null, flow.count, virtualization.count, virtualizationAnalysis, transformerReports
    )
  }
}
