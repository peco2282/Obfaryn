package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.assembly.*
import com.peco2282.obfaryn.api.transformer.RenamingTransformer
import com.peco2282.obfaryn.standard.StandardPackage
import com.peco2282.obfaryn.api.MapPlatformOptions
import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.api.transformer.PipelineConfigurer
import com.peco2282.obfaryn.api.transformer.TransformerReport
import com.peco2282.obfaryn.api.transformer.TransformationInputs
import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy
import com.peco2282.obfaryn.core.integrity.IntegrityProtection
import com.peco2282.obfaryn.core.jar.JarReader
import com.peco2282.obfaryn.core.jar.JarWriter
import com.peco2282.obfaryn.core.io.ArtifactPaths.sameFile
import com.peco2282.obfaryn.core.keep.KeepRules
import com.peco2282.obfaryn.core.platform.Platforms
import com.peco2282.obfaryn.core.platform.PlatformExtensionApplier
import com.peco2282.obfaryn.core.rename.RenameTransformer
import com.peco2282.obfaryn.core.transformer.*
import com.peco2282.obfaryn.core.protection.*
import com.peco2282.obfaryn.transformer.linkage.PrelinkRuntimeTransformer
import com.peco2282.obfaryn.api.protection.*
import com.peco2282.obfaryn.transformer.arithmetic.IntegerArithmeticTransformer
import com.peco2282.obfaryn.transformer.ConstantFieldTransformer
import com.peco2282.obfaryn.transformer.CallIndirectionTransformer
import com.peco2282.obfaryn.transformer.ControlFlowTransformer
import com.peco2282.obfaryn.transformer.MetadataStripTransformer
import com.peco2282.obfaryn.transformer.NumberObfuscationTransformer
import com.peco2282.obfaryn.transformer.StringEncryptionTransformer
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
  private val packages: List<TransformationPackage>? = null,
  private val protectedInputs: List<Path> = emptyList(),
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
    require(!config.prelinkCalls || config.callIndirection) { "prelinkCalls requires callIndirection" }
    val strong = config.protectionLevel == ProtectionLevel.STRONG
    val defaultMapping = output.resolveSibling(output.fileName.toString().removeSuffix(".jar") + ".mapping.txt")
    val reportPath = config.virtualizationReport ?: if (strong)
      output.resolveSibling(output.fileName.toString().removeSuffix(".jar") + ".virtualization.json") else null
    val destinations = listOfNotNull(
      "Output JAR" to output, "Mapping" to (mapping ?: defaultMapping),
      reportPath?.let { "Virtualization report" to it },
      config.transformationReport?.let { "Transformation report" to it },
    )
    val protectedPaths = listOfNotNull(input, config.integrityPrivateKey, config.integrityPublicKey) + config.classpath + protectedInputs
    for ((index, destination) in destinations.withIndex()) {
      val (label, path) = destination
      require(!Files.isDirectory(path) && protectedPaths.none { sameFile(it, path) }) {
        "$label must not overwrite inputs, classpath or keys, or refer to a directory"
      }
      require(destinations.take(index).none { sameFile(it.second, path) }) {
        "$label and other outputs must use different paths"
      }
    }
    val controlFlow = config.effectiveControlFlowMode != ControlFlowMode.NONE
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
    require(Files.isRegularFile(input)) { "Input JAR does not exist: $input" }
    val context = jarReader.read(input)
    val classesLoaded = context.classes.size
    val resourcesLoaded = context.resources.size
    val primary = Platforms.select(config.platform, context)
    val platform = com.peco2282.obfaryn.core.platform.CompositePlatform(
      listOf(primary) + config.additionalPlatforms.map { id ->
        require(id != "auto") { "Additional platforms must be selected explicitly" }
        Platforms.select(id, context)
      })
    val platformOptions = MapPlatformOptions(config.platformOptions)
    val appliedPlatformExtensions = PlatformExtensionApplier.apply(platform, context, platformOptions)
    ObfuscationAnnotationPolicy.validate(context.classes.values)
    val keep = KeepRules()
    context.namePreservation = com.peco2282.obfaryn.core.keep.KeepNamePreservation(keep)
    config.keep.forEach(keep::add)
    platform.configure(context, platformOptions, keep)
    config.platformConfiguration?.invoke(keep)
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
    val applicationRoots = (entrypoints.mapNotNull { it.substringBeforeLast('/', "").takeIf(String::isNotEmpty) } +
        config.applicationPackages.map { it.replace('.', '/').trimEnd('/') }).filter(String::isNotEmpty).distinct()
    require(!(strong || config.callIndirection || config.integerArithmetic) || applicationRoots.isNotEmpty()) {
      "Strong protection, call indirection and integer arithmetic require an application package or a platform/manifest entrypoint"
    }
    val annotatedNoObfuscationClasses = context.classes.values
      .filter(ObfuscationAnnotationPolicy::noObfuscation).mapTo(linkedSetOf()) { it.name }
    val excludedClasses = (context.classes.keys.filter { name -> classExclusions.any { it.matches(name) } }.toSet() +
      annotatedNoObfuscationClasses +
      (if (config.prelinkCalls && context.hasClass(PrelinkRuntimeTransformer.RUNTIME_NAME)) setOf(PrelinkRuntimeTransformer.RUNTIME_NAME) else emptySet()) + if (strong) context.classes.keys.filter { name ->
        applicationRoots.none { name.startsWith("$it/") }
      }.toSet() else emptySet()).toMutableSet()
    val excludedResources =
      context.resources.keys.filter { name -> resourceExclusions.any { it.matches(name) } }.toMutableSet()
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
    val selectors = config.virtualizeMethods
    val standardSelected = customTransformers == null && (packages == null || packages.any { it is StandardPackage })
    val virtualizationAnalysis = VirtualizationAnalyzer(
      selectors, config.excludeVirtualization, platformVirtualizationExclusions)
      .analyze(context.classes.values.filter { it.name !in excludedClasses &&
        (standardSelected || config.virtualizationAnalyzeOnly || config.virtualizationSelectionMode == VirtualizationSelectionMode.ANALYZE_ONLY) })
    val virtualizationRequested = virtualizationAnalysis.selected > 0
    reportPath?.let(virtualizationAnalysis::write)
    val analyzeOnly = config.virtualizationAnalyzeOnly || config.virtualizationSelectionMode == VirtualizationSelectionMode.ANALYZE_ONLY
    val selectionMode = config.virtualizationSelectionMode
    val virtualizationWarnings = virtualizationAnalysis.requireEligible(
      if (analyzeOnly) VirtualizationSelectionMode.ANALYZE_ONLY else selectionMode)
    if (analyzeOnly) {
      appliedPlatformExtensions.cleanup()
      return ObfuscationResult(classesLoaded, resourcesLoaded, platform.id, 0, 0, 0, 0, 0,
        output, null, virtualizationWarnings, virtualizationAnalysis = virtualizationAnalysis)
    }
    require((config.integrityPrivateKey == null) == (config.integrityPublicKey == null)) {
      "Both integrityPrivateKey and integrityPublicKey are required"
    }
    val warnings = ReflectionScanner.scan(context, keep).toMutableList()
    if (virtualizationRequested) warnings += virtualizationWarnings
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
    if (stripAllMetadata && metadataClasses.isNotEmpty()) {
      warnings += "STRIP kotlin.Metadata on ${metadataClasses.size} classes may break Kotlin reflection and metadata-dependent libraries such as kotlinx.serialization"
    }
    val signatures = context.resources.keys.filter { Regex("(?i)^META-INF/[^/]+\\.(SF|RSA|DSA|EC)$").matches(it) }
    if (signatures.isNotEmpty()) warnings += "Removed invalid JAR signatures: ${signatures.joinToString()}"
    val random = Random(config.seed ?: SecureRandom().nextLong())
    context.transformationInputs = object : TransformationInputs {
      override fun isApplicationClass(internalName: String) =
        applicationRoots.any { (context.originalClassNames[internalName] ?: internalName).startsWith("$it/") }
      override fun keepConstant(owner: String, field: String) = keep.field(owner, field)
      override fun methodIdentity(owner: org.objectweb.asm.tree.ClassNode, method: org.objectweb.asm.tree.MethodNode) =
        context.originalMethodIdentities[owner.name + "." + method.name + method.desc]
          ?: (owner.name + "." + method.name + method.desc)
    }
    context.transformationPolicy = FeaturePolicy(context.transformationPolicy, config.transformationBudget,
      config.protectionRules, context.transformationInputs::methodIdentity)
    // Fail invalid annotation budgets before emitting any transformed artifact.
    for (owner in context.classes.values) for (method in owner.methods) context.transformationPolicy.budget(owner, method)
    val processors = mutableListOf<OutputProcessor>()
    val assembly = object : AssemblyContext {
      override val input = input
      override val output = output
      override val reproducible = config.reproducible
      override fun protectInput(path: Path) {
        require(destinations.none { sameFile(path, it.second) }) { "Artifacts must not overwrite package inputs: $path" }
      }
      override fun excludeClass(internalName: String) { excludedClasses += internalName; keep.keepClass(internalName) }
      override fun excludeResource(path: String) { excludedResources += path; keep.keepResource(path) }
      override val context = context
      override val configuration = keep
      override val options = platformOptions
      override val applicationPackages = applicationRoots
      override val classpath = config.classpath
      override val random = random
      override fun outputProcessor(processor: OutputProcessor) { processors += processor }
    }
    val orderedPipeline = TransformerPipeline().apply {
      if (customTransformers != null) customTransformers!!.forEach(::add)
      else (packages ?: listOf(StandardPackage(config, keep, virtualizationRequested))).forEach { it.configure(assembly, this) }
      platform.extensions().filterIsInstance<PlatformPackages>().flatMap { it.packages(context, platformOptions) }
        .forEach { it.configure(assembly, this) }
      configurePipeline?.invoke(this)
      pipelineConfigurers.forEach { it.configure(this) }
    }.ordered()
    val transformerReports = PipelineExecutor().execute(context, orderedPipeline, excludedClasses, excludedResources,
      config.transformationReport != null)
    val renamer = orderedPipeline.filterIsInstance<RenamingTransformer>().singleOrNull()
    val mappingText = renamer?.mappingText().orEmpty()
    fun count(id: String) = (transformerReports.firstOrNull { it.id == id }?.metrics?.get("transformed") ?: 0L).toInt()
    config.transformationReport?.let { TransformationReportWriter.write(it, transformerReports) }
    warnings += transformerReports.flatMap { it.warnings }
    processors.forEach { it.beforeWrite(context) }
    if (virtualizationRequested && count("virtualization") == 0)
      warnings += if (virtualizationAnalysis.selected == 0) "No methods virtualized: selectors matched no non-excluded methods"
        else "No methods virtualized: selectors matched ${virtualizationAnalysis.selected} methods, but none were eligible"
    appliedPlatformExtensions.cleanup()
    if (!config.dryRun) jarWriter.write(
      context, output, config.reproducible, config.verifyOutput || strong,
      config.recomputeFrames || controlFlow || virtualizationRequested ||
        orderedPipeline.any { it.requiresFrameRecomputation }, config.classpath
    )
    if (!config.dryRun) processors.forEach { it.afterWrite(output) }
    val renameExecuted = renamer != null
    val mappingOutput = if (config.dryRun) null else mapping
      ?: if (renameExecuted && mappingText.isNotEmpty())
        output.resolveSibling(output.fileName.toString().removeSuffix(".jar") + ".mapping.txt") else null
    if (mappingOutput != null) {
      mappingOutput.parent?.let(Files::createDirectories)
      mappingOutput.writeText(mappingText)
    }
    return ObfuscationResult(
      classesLoaded, resourcesLoaded, platform.id,
      count("string-encryption"), count("number-obfuscation"),
      renamer?.classMapping?.size ?: 0, renamer?.methodMapping?.size ?: 0, renamer?.fieldMapping?.size ?: 0,
      output, mappingOutput, warnings,
      if (config.dryRun) mappingText else null,
      count("control-flow"), count("virtualization"), virtualizationAnalysis, transformerReports
    )
  }
}
