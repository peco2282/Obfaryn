package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.MapPlatformOptions
import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.api.transformer.PipelineConfigurer
import com.peco2282.obfaryn.api.transformer.TransformerReport
import com.peco2282.obfaryn.api.transformer.TransformationInputs
import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy
import com.peco2282.obfaryn.core.integrity.IntegrityProtection
import com.peco2282.obfaryn.core.jar.JarReader
import com.peco2282.obfaryn.core.jar.JarWriter
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
    fun sameFile(a: Path, b: Path) = a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize() ||
      Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b)
    config.transformationReport?.let { report ->
      val defaultMapping = output.resolveSibling(output.fileName.toString().removeSuffix(".jar") + ".mapping.txt")
      val virtualReport = config.virtualizationReport ?: if (strong)
        output.resolveSibling(output.fileName.toString().removeSuffix(".jar") + ".virtualization.json") else null
      val protectedPaths = listOfNotNull(input, output, mapping ?: defaultMapping, virtualReport,
        config.integrityPrivateKey, config.integrityPublicKey) + config.classpath
      require(!Files.isDirectory(report) && protectedPaths.none { sameFile(it, report) }) {
        "Transformation report must not overwrite inputs, classpath, keys, output, mapping or virtualization report"
      }
    }
    val controlFlow = config.controlFlow || config.lightweightControlFlow || strong
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
    val excludedClasses = context.classes.keys.filter { name -> classExclusions.any { it.matches(name) } }.toSet() +
      annotatedNoObfuscationClasses +
      (if (config.prelinkCalls && context.hasClass(PrelinkRuntimeTransformer.RUNTIME_NAME)) setOf(PrelinkRuntimeTransformer.RUNTIME_NAME) else emptySet()) + if (strong) context.classes.keys.filter { name ->
        applicationRoots.none { name.startsWith("$it/") }
      }.toSet() else emptySet()
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
    val selectors = config.virtualizeMethods
    val virtualizationAnalysis = VirtualizationAnalyzer(
      selectors, config.excludeVirtualization, platformVirtualizationExclusions)
      .analyze(context.classes.values.filter { it.name !in excludedClasses })
    val virtualizationRequested = virtualizationAnalysis.selected > 0
    val reportPath = config.virtualizationReport ?: if (strong)
      output.resolveSibling(output.fileName.toString().removeSuffix(".jar") + ".virtualization.json") else null
    require(reportPath == null || config.transformationReport?.toAbsolutePath()?.normalize() != reportPath.toAbsolutePath().normalize()) {
      "Transformation and virtualization reports must use different paths"
    }
    reportPath?.let { report ->
      require(listOfNotNull(input, output, mapping).none { it.toAbsolutePath().normalize() == report.toAbsolutePath().normalize() }) {
        "Virtualization report must not overwrite the input, output, or mapping"
      }
    }
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
    val integrity = config.integrityPrivateKey?.let {
      IntegrityProtection(it, config.integrityPublicKey!!)
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
    val audit = config.transformationReport != null || config.transformationBudget != TransformationBudget() ||
      config.protectionRules.any { it.budget != null } || context.classes.values.any { owner ->
        (owner.visibleAnnotations.orEmpty() + owner.invisibleAnnotations.orEmpty()).any { it.desc == FeaturePolicy.BUDGET } ||
          owner.methods.any { method -> (method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty()).any { it.desc == FeaturePolicy.BUDGET } }
      }
    val baseline = if (audit) context.classes.values.flatMap { owner -> owner.methods.map { method ->
      context.transformationInputs.methodIdentity(owner, method) to MethodSize.of(method)
    } }.toMap() else emptyMap()
    val strings = StringEncryptionTransformer(config.stringRandom ?: random, config.stringEncryptionStrategy)
    val numbers = NumberObfuscationTransformer(config.numberRandom ?: random, config.numberStrategy, config.numberSelector)
    val constantFields =
      ConstantFieldTransformer(
        strings = config.stringEncryption && config.stringConstantFields,
        numbers = config.numberObfuscation && config.numberConstantFields,
      )
    val rename = RenameTransformer(config, keep)
    val flow = ControlFlowTransformer(random, config.lightweightControlFlow)
    // Scope is captured before renaming; the final pass receives renamed classes and annotations.
    val callClasses = context.classes.keys.filter { name ->
      name !in excludedClasses && applicationRoots.any { name.startsWith("$it/") }
    }
    val calls = CallIndirectionTransformer(random, prelink = config.prelinkCalls) { name ->
      callClasses.any { (rename.classMapping[it] ?: it) == name }
    }
    val arithmetic = IntegerArithmeticTransformer(random, config.maxArithmeticOperationsPerMethod)
    val virtualization = VirtualizationTransformer(
      selectors, random, config.virtualizationRuntimeGroupSize, selectionMode,
      config.classpath + listOf(input), config.excludeVirtualization, platformVirtualizationExclusions
    )
    val pipeline = customTransformers ?: listOfNotNull(
      virtualization.takeIf { virtualizationRequested },
      MetadataStripTransformer(
        stripDebugInfo = config.stripDebugInfo, stripKotlinMetadata = stripAllMetadata,
        stripGenericSignatures = config.stripGenericSignatures,
        stripMethodParameters = config.stripMethodParameters
      )
        .takeIf { config.stripDebugInfo || stripAllMetadata || config.stripGenericSignatures || config.stripMethodParameters },
      constantFields.takeIf {
        applicationRoots.isNotEmpty() &&
          (config.stringEncryption && config.stringConstantFields || config.numberObfuscation && config.numberConstantFields)
      },
      strings.takeIf { config.stringEncryption },
      numbers.takeIf { config.numberObfuscation },
      arithmetic.takeIf { config.integerArithmetic },
      flow.takeIf { controlFlow },
      rename.takeIf { config.renameClasses || config.renameMethods || config.renameFields },
      calls.takeIf { config.callIndirection },
      PrelinkRuntimeTransformer().takeIf { config.prelinkCalls },
    )
    val orderedPipeline = TransformerPipeline(emptyList()).apply {
      pipeline.forEach(::add)
      configurePipeline?.invoke(this)
      pipelineConfigurers.forEach { it.configure(this) }
    }.ordered()
    val transformerReports = mutableListOf<TransformerReport>()
    for (transformer in orderedPipeline) {
      try {
        val originalNames = if (transformer === rename) context.classes.values.associate { owner ->
          owner.name to owner.methods.map { context.transformationInputs.methodIdentity(owner, it) }
        } else emptyMap()
        val feature = transformer.isolatedBodyFeature
        fun operations(): Int? = when (transformer) {
          is StringEncryptionTransformer -> transformer.count
          is NumberObfuscationTransformer -> transformer.count
          is ConstantFieldTransformer -> transformer.count
          is ControlFlowTransformer -> transformer.count
          is IntegerArithmeticTransformer -> transformer.count
          else -> null
        }
        val effective = if (feature != null) BudgetedPass(transformer, feature, baseline,
          config.transformationReport != null, ::operations) else transformer
        effective.transform(if (transformer === rename) context else FilteredContext(context, excludedClasses, excludedResources))
        if (transformer === rename) for ((owner, identities) in originalNames) {
          val mapped = context.findClass(rename.classMapping[owner] ?: owner) ?: continue
          context.originalClassNames[mapped.name] = context.originalClassNames[owner] ?: owner
          mapped.methods.forEachIndexed { index, method -> identities.getOrNull(index)?.let {
            context.originalMethodIdentities[mapped.name + "." + method.name + method.desc] = it
          } }
        }
        val baseReport = effective.report()
        val report = if (config.transformationReport != null && feature != null) {
          val excludedRows = excludedClasses.flatMap { name ->
            val owner = context.findClass(name) ?: return@flatMap emptyList()
            owner.methods.map { method ->
              val size = MethodSize.of(method)
              MethodTransformationReport(context.transformationInputs.methodIdentity(owner, method), "skipped",
                if (ObfuscationAnnotationPolicy.noObfuscation(owner)) "annotation:NoObfuscation" else "class-exclusion-or-application-scope",
                size.instructions, size.instructions, size.branches, size.branches, size.bytes, size.bytes)
            }
          }
          baseReport.copy(methods = baseReport.methods + excludedRows)
        } else baseReport
        val count = if (effective !== transformer) null else when (transformer) {
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
    config.transformationReport?.let { TransformationReportWriter.write(it, transformerReports) }
    warnings += transformerReports.flatMap { it.warnings }
    warnings += rename.warnings
    integrity?.inject(context, excludedClasses, random)
    if (virtualizationRequested && virtualization.count == 0)
      warnings += if (virtualizationAnalysis.selected == 0) "No methods virtualized: selectors matched no non-excluded methods"
        else "No methods virtualized: selectors matched ${virtualizationAnalysis.selected} methods, but none were eligible"
    appliedPlatformExtensions.cleanup()
    if (!config.dryRun) jarWriter.write(
      context, output, config.reproducible, config.verifyOutput || strong,
      config.recomputeFrames || controlFlow || virtualizationRequested ||
        orderedPipeline.any { it.requiresFrameRecomputation }, config.classpath
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
      classesLoaded, resourcesLoaded, platform.id,
      (transformerReports.firstOrNull { it.id == strings.id }?.metrics?.get("transformed") ?: strings.count.toLong()).toInt(),
      (transformerReports.firstOrNull { it.id == numbers.id }?.metrics?.get("transformed") ?: numbers.count.toLong()).toInt(),
      rename.classMapping.size, rename.methodMapping.size, rename.fieldMapping.size, output, mappingOutput, warnings,
      if (config.dryRun) rename.mappingText() else null,
      (transformerReports.firstOrNull { it.id == flow.id }?.metrics?.get("transformed") ?: flow.count.toLong()).toInt(), virtualization.count, virtualizationAnalysis, transformerReports
    )
  }
}
