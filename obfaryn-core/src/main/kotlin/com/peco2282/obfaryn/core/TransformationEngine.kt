package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.*
import com.peco2282.obfaryn.api.assembly.*
import com.peco2282.obfaryn.api.protection.*
import com.peco2282.obfaryn.api.transformer.*
import com.peco2282.obfaryn.core.io.ArtifactPaths
import com.peco2282.obfaryn.core.jar.*
import com.peco2282.obfaryn.core.keep.KeepRules
import com.peco2282.obfaryn.core.platform.*
import com.peco2282.obfaryn.core.protection.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Random

/** Execution settings contain no standard algorithm switches. An empty package list runs no passes. */
data class EngineOptions(
  val platform: String = "auto",
  val additionalPlatforms: List<Platform> = emptyList(),
  val platformOptions: Map<String, String> = emptyMap(),
  val classpath: List<Path> = emptyList(),
  val applicationPackages: List<String> = emptyList(),
  val seed: Long? = null,
  val verifyOutput: Boolean = true,
  val reproducible: Boolean = true,
  val dryRun: Boolean = false,
  val budget: TransformationBudget = TransformationBudget(),
  val protectionRules: List<ProtectionRule> = emptyList(),
  val configuration: (PlatformConfiguration.() -> Unit)? = null,
)

data class EngineResult(
  val output: Path,
  val platforms: List<String>,
  val transformerReports: List<TransformerReport>,
  val mapping: String,
)

/** Standard-free JAR host. Recipes, platform tools and user callbacks share one registry per run. */
class TransformationEngine(private val options: EngineOptions = EngineOptions()) {
  fun transform(input: Path, output: Path, packages: List<TransformationPackage> = emptyList(),
    pipelineConfigurers: List<PipelineConfigurer> = emptyList()): EngineResult {
    require(Files.isRegularFile(input)) { "Input JAR does not exist: $input" }
    require(!Files.isDirectory(output) && (listOf(input) + options.classpath).none { ArtifactPaths.sameFile(it, output) }) {
      "Output JAR must not overwrite inputs or dependencies, or refer to a directory"
    }
    val context = JarReader().read(input)
    val selected = listOf(Platforms.select(options.platform, context)) + options.additionalPlatforms
    require(selected.map { it.id }.distinct().size == selected.size) { "Duplicate selected platforms" }
    require(selected.all { it.metadata.apiVersion == PlatformApi.VERSION }) { "Unsupported platform API version" }
    val platformOptions = MapPlatformOptions(options.platformOptions)
    val composite = CompositePlatform(selected)
    val applied = PlatformExtensionApplier.apply(composite, context, platformOptions)
    com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy.validate(context.classes.values)
    val keep = KeepRules()
    context.namePreservation = com.peco2282.obfaryn.core.keep.KeepNamePreservation(keep)
    selected.forEach { it.configure(context, platformOptions, keep) }
    options.configuration?.invoke(keep)
    val entrypoints = selected.flatMap { it.entrypoints(context, platformOptions) }.toMutableList()
    context.resources["META-INF/MANIFEST.MF"]?.let { entry ->
      val manifest = java.util.jar.Manifest(entry.data.inputStream())
      for (key in listOf("Main-Class", "Premain-Class", "Agent-Class", "Launcher-Agent-Class")) {
        manifest.mainAttributes.getValue(key)?.let { name ->
          keep.keepClassNameAndPublicMembers(name)
          entrypoints += name.replace('.', '/')
        }
      }
    }
    for ((path, resource) in context.resources) if (path.startsWith("META-INF/services/")) {
      keep.keepClassNameAndPublicMembers(path.removePrefix("META-INF/services/"))
      resource.data.toString(Charsets.UTF_8).lineSequence().map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }.forEach(keep::keepClassNameAndPublicMembers)
    }
    val roots = (options.applicationPackages.map { it.replace('.', '/').trimEnd('/') } +
      entrypoints.mapNotNull { it.substringBeforeLast('/', "").takeIf(String::isNotEmpty) }).filter(String::isNotEmpty).distinct()
    context.transformationInputs = object : TransformationInputs {
      override fun isApplicationClass(internalName: String) = roots.any {
        (context.originalClassNames[internalName] ?: internalName).startsWith("$it/")
      }
      override fun keepConstant(owner: String, field: String) = keep.field(owner, field)
      override fun methodIdentity(owner: org.objectweb.asm.tree.ClassNode, method: org.objectweb.asm.tree.MethodNode) =
        context.originalMethodIdentities[owner.name + "." + method.name + method.desc] ?: (owner.name + "." + method.name + method.desc)
    }
    context.transformationPolicy = FeaturePolicy(context.transformationPolicy, options.budget,
      options.protectionRules, context.transformationInputs::methodIdentity)
    for (owner in context.classes.values) for (method in owner.methods) context.transformationPolicy.budget(owner, method)
    val excludedClasses = linkedSetOf<String>()
    val excludedResources = linkedSetOf<String>()
    val processors = mutableListOf<OutputProcessor>()
    val assembly = object : AssemblyContext {
      override val input = input
      override val output = output
      override val reproducible = this@TransformationEngine.options.reproducible
      override fun protectInput(path: Path) {
        require(!ArtifactPaths.sameFile(path, output)) { "Output JAR must not overwrite package inputs: $path" }
      }
      override fun excludeClass(internalName: String) { excludedClasses += internalName; keep.keepClass(internalName) }
      override fun excludeResource(path: String) { excludedResources += path; keep.keepResource(path) }
      override val context = context
      override val configuration = keep
      override val options = platformOptions
      override val applicationPackages = roots
      override val classpath = this@TransformationEngine.options.classpath
      override val random = Random(this@TransformationEngine.options.seed ?: SecureRandom().nextLong())
      override fun outputProcessor(processor: OutputProcessor) { processors += processor }
    }
    val registry = TransformerPipeline()
    packages.forEach { it.configure(assembly, registry) }
    composite.extensions().filterIsInstance<PlatformPackages>()
      .flatMap { it.packages(context, platformOptions) }.forEach { it.configure(assembly, registry) }
    pipelineConfigurers.forEach { it.configure(registry) }
    val passes = registry.ordered()
    val reports = PipelineExecutor().execute(context, passes, excludedClasses, excludedResources, detailed = true)
    processors.forEach { it.beforeWrite(context) }
    applied.cleanup()
    if (!options.dryRun) {
      JarWriter().write(context, output, options.reproducible, options.verifyOutput,
        passes.any { it.requiresFrameRecomputation }, options.classpath)
      processors.forEach { it.afterWrite(output) }
    }
    return EngineResult(output, selected.map { it.id }, reports,
      passes.filterIsInstance<RenamingTransformer>().joinToString("") { it.mappingText() })
  }
}
