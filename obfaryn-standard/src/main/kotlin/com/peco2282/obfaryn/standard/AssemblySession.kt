package com.peco2282.obfaryn.standard

import com.peco2282.obfaryn.api.MapPlatformOptions
import com.peco2282.obfaryn.api.assembly.AssemblyArtifact
import com.peco2282.obfaryn.api.assembly.AssemblyContext
import com.peco2282.obfaryn.api.assembly.AssemblyResult
import com.peco2282.obfaryn.api.assembly.OutputProcessor
import com.peco2282.obfaryn.api.assembly.PlatformPackages
import com.peco2282.obfaryn.api.transformer.RenamingTransformer
import com.peco2282.obfaryn.api.transformer.TransformationInputs
import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.api.transformer.TransformerReport
import com.peco2282.obfaryn.core.ObfuscationConfig
import com.peco2282.obfaryn.core.PipelineExecutor
import com.peco2282.obfaryn.core.ReflectionScanner
import com.peco2282.obfaryn.core.TransformationPackages
import com.peco2282.obfaryn.core.TransformerPipeline
import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy
import com.peco2282.obfaryn.core.assembly.ArtifactSplitter
import com.peco2282.obfaryn.core.assembly.AssemblyPaths
import com.peco2282.obfaryn.core.assembly.ProgramUnion
import com.peco2282.obfaryn.core.assembly.WholeProgramView
import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.io.ArtifactPaths
import com.peco2282.obfaryn.core.jar.JarReader
import com.peco2282.obfaryn.core.keep.KeepNamePreservation
import com.peco2282.obfaryn.core.keep.KeepRules
import com.peco2282.obfaryn.core.platform.CompositePlatform
import com.peco2282.obfaryn.core.platform.PlatformExtensionApplier
import com.peco2282.obfaryn.core.platform.Platforms
import com.peco2282.obfaryn.core.protection.FeaturePolicy
import com.peco2282.obfaryn.core.rename.RenameTransformer
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Random
import java.util.jar.Manifest

/**
 * One artifact of a cross-artifact assembly: its paths, its stable id, and its own body-pass
 * [ObfuscationConfig] (platform selection, string/number/control-flow choices, flags, body seed).
 *
 * The config's renaming switches are not used here — renaming is decided once for the whole program
 * by [AssemblySession.renameConfig], not per artifact. Per-artifact `callIndirection`/`prelinkCalls`
 * are rejected, because those passes must run after renaming and renaming is global (see §8.3).
 *
 * Body-pass package selection is per artifact, mirroring single-artifact `obfuscate`: [noDefaultPackage]
 * starts this artifact from an empty pipeline instead of the standard recipe, and [packageIds] names
 * additional provider-supplied packages (resolved on the host/extension classloader). The global
 * rename runs regardless; any renamer a selected package registers is dropped, as for the standard one.
 */
data class AssemblyModule(
  override val stableId: String,
  override val input: Path,
  override val output: Path,
  val config: ObfuscationConfig = ObfuscationConfig(),
  val packageIds: List<String> = emptyList(),
  val noDefaultPackage: Boolean = false,
) : AssemblyArtifact

private class AssemblyRun(
  override val outputs: Map<String, Path>,
  override val mapping: String,
  override val reports: Map<String, List<TransformerReport>>,
) : AssemblyResult

/**
 * Obfuscates several artifacts in a single naming universe so cross-artifact references survive.
 *
 * Names are global and owned by the session: one keep set, one hierarchy, one rename, one mapping.
 * Bodies, resources and I/O are local to each artifact. The run has three phases over a shared
 * [KeepRules]:
 *
 * - **Phase A (per artifact):** read the JAR, run platform detection/configuration against the
 *   whole-program view, collect manifest/service/platform keep rules, and run every body pass
 *   *except* renaming.
 * - **Phase B (once):** union every artifact's classes into one [ProgramUnion] and run a single
 *   [RenameTransformer] over it, producing the global mapping.
 * - **Phase C (per artifact):** split the renamed union back out by origin, remap each artifact's
 *   `module-info`, and write its JAR.
 *
 * Writing the JARs is not atomic: a mid-run failure can leave only some of them written. This is not
 * the distribution model where an obfuscated API is shipped and rebuilt later; it requires every
 * artifact to be present at assembly time.
 *
 * @param modules the artifacts to assemble, in any order; ids must be unique and non-blank.
 * @param renameConfig the session-wide rename settings (class/method/field renaming, package
 *   flattening and target, rename seed, application-package roots) and the external [classpath] for
 *   hierarchy and frame resolution. Sibling artifacts must not appear on this classpath: they are in
 *   the union already.
 */
class AssemblySession(
  private val modules: List<AssemblyModule>,
  private val renameConfig: ObfuscationConfig = ObfuscationConfig(),
) {
  init {
    require(modules.isNotEmpty()) { "An assembly needs at least one artifact" }
    require(modules.all { it.stableId.isNotBlank() }) { "Every artifact needs a non-blank stableId" }
    require(modules.map { it.stableId }.distinct().size == modules.size) { "Artifact stableIds must be unique" }
    for (module in modules) {
      require(!module.config.callIndirection && !module.config.prelinkCalls) {
        "Artifact '${module.stableId}': callIndirection/prelinkCalls are not supported in a cross-artifact assembly, " +
          "because they must run after the global rename"
      }
      require(module.config.integrityPrivateKey == null && module.config.integrityPublicKey == null) {
        "Artifact '${module.stableId}': integrity sealing is not supported in a cross-artifact assembly"
      }
    }
  }

  private val externalClasspath = renameConfig.classpath

  fun run(): AssemblyResult {
    // Phase 0: validate the whole destination set and read every artifact before any body work.
    for (module in modules) require(Files.isRegularFile(module.input)) { "Input JAR does not exist: ${module.input}" }
    val keyPaths = modules.flatMap { listOfNotNull(it.config.integrityPrivateKey, it.config.integrityPublicKey) }
    AssemblyPaths.validate(modules.map { it.stableId to it.output },
      modules.map { it.input } + externalClasspath + keyPaths)
    val contexts = modules.associate { it.stableId to JarReader().read(it.input) }
    val wholeView = WholeProgramView.of(contexts.values)
    contexts.values.forEach { it.wholeProgramView = wholeView }

    // Phase A: body-only transformation of each artifact, accumulating global keep rules.
    val keep = KeepRules()
    val reports = LinkedHashMap<String, List<TransformerReport>>()
    val roots = LinkedHashSet<String>()
    for (module in modules) roots += bodyPhase(module, contexts.getValue(module.stableId), keep, reports)

    // Phase B: one rename over the union of every artifact's post-body classes.
    val union = ProgramUnion.build(modules.map { it.stableId to contexts.getValue(it.stableId) })
    val unionContext = union.context
    unionContext.namePreservation = KeepNamePreservation(keep)
    val rootList = roots.toList()
    unionContext.transformationInputs = object : TransformationInputs {
      override fun isApplicationClass(internalName: String) =
        rootList.any { (unionContext.originalClassNames[internalName] ?: internalName).startsWith("$it/") }
      override fun methodIdentity(owner: ClassNode, method: MethodNode) =
        unionContext.originalMethodIdentities[owner.name + "." + method.name + method.desc]
          ?: (owner.name + "." + method.name + method.desc)
    }
    val renamer = RenameTransformer(
      renameConfig.copy(classpath = externalClasspath, applicationPackages = rootList), keep)
    PipelineExecutor().execute(unionContext, listOf(renamer))

    // Phase C: split the renamed union back out and write one JAR per artifact.
    val splitter = ArtifactSplitter(unionContext, union.origins, renamer.classMapping, renamer.packageMapping)
    for (module in modules) {
      val context = contexts.getValue(module.stableId)
      splitter.write(
        module.stableId,
        context.resources,
        context.findClass(ProgramUnion.MODULE_INFO),
        module.output,
        renameConfig.reproducible,
        module.config.verifyOutput,
        frameRecompute.getValue(module.stableId),
        externalClasspath,
      )
      // Match single-artifact write ordering: output processors see the finished JAR after it lands.
      outputProcessors.getValue(module.stableId).forEach { it.afterWrite(module.output) }
    }

    return AssemblyRun(modules.associate { it.stableId to it.output }, renamer.mappingText(), reports)
  }

  /** Runs one artifact's platform setup and body passes (never the renamer), returning its app roots. */
  private fun bodyPhase(
    module: AssemblyModule, context: ObfuscationContext, keep: KeepRules,
    reports: MutableMap<String, List<TransformerReport>>,
  ): List<String> {
    val config = module.config
    val options = MapPlatformOptions(config.platformOptions)
    val primary = Platforms.select(config.platform, context)
    val platform = CompositePlatform(listOf(primary) + config.additionalPlatforms.map { id ->
      require(id != "auto") { "Additional platforms must be selected explicitly" }
      Platforms.select(id, context)
    })
    val applied = PlatformExtensionApplier.apply(platform, context, options)
    ObfuscationAnnotationPolicy.validate(context.classes.values)
    context.namePreservation = KeepNamePreservation(keep)
    config.keep.forEach(keep::add)
    platform.configure(context, options, keep)
    config.platformConfiguration?.invoke(keep)
    val entrypoints = platform.entrypoints(context, options).toMutableSet()
    context.resources["META-INF/MANIFEST.MF"]?.let { entry ->
      val manifest = Manifest(entry.data.inputStream())
      for (name in listOf("Main-Class", "Premain-Class", "Agent-Class", "Launcher-Agent-Class")) {
        manifest.mainAttributes.getValue(name)?.let {
          keep.keepClassNameAndPublicMembers(it)
          entrypoints += it.replace('.', '/')
        }
      }
    }
    for ((path, resource) in context.resources) if (path.startsWith("META-INF/services/")) {
      keep.keepClassNameAndPublicMembers(path.removePrefix("META-INF/services/"))
      resource.data.toString(Charsets.UTF_8).lineSequence().map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }.forEach(keep::keepClassNameAndPublicMembers)
    }
    val roots = (entrypoints.mapNotNull { it.substringBeforeLast('/', "").takeIf(String::isNotEmpty) } +
      config.applicationPackages.map { it.replace('.', '/').trimEnd('/') }).filter(String::isNotEmpty).distinct()
    context.transformationInputs = object : TransformationInputs {
      override fun isApplicationClass(internalName: String) =
        roots.any { (context.originalClassNames[internalName] ?: internalName).startsWith("$it/") }
      override fun keepConstant(owner: String, field: String) = keep.field(owner, field)
      override fun methodIdentity(owner: ClassNode, method: MethodNode) =
        context.originalMethodIdentities[owner.name + "." + method.name + method.desc]
          ?: (owner.name + "." + method.name + method.desc)
    }
    context.transformationPolicy = FeaturePolicy(context.transformationPolicy, config.transformationBudget,
      config.protectionRules, context.transformationInputs::methodIdentity)
    for (owner in context.classes.values) for (method in owner.methods) context.transformationPolicy.budget(owner, method)

    val excludedClasses = linkedSetOf<String>()
    val excludedResources = linkedSetOf<String>()
    val processors = mutableListOf<OutputProcessor>()
    val assembly = object : AssemblyContext {
      override val input = module.input
      override val output = module.output
      override val reproducible = renameConfig.reproducible
      override fun protectInput(path: Path) {
        require(modules.none { ArtifactPaths.sameFile(path, it.output) }) {
          "Artifact '${module.stableId}': a package input must not be overwritten by any artifact output: $path"
        }
      }
      override fun excludeClass(internalName: String) { excludedClasses += internalName; keep.keepClass(internalName) }
      override fun excludeResource(path: String) { excludedResources += path; keep.keepResource(path) }
      override val context = context
      override val configuration = keep
      override val options = options
      override val applicationPackages = roots
      override val classpath = config.classpath
      override val random = Random(config.seed ?: SecureRandom().nextLong())
      override fun outputProcessor(processor: OutputProcessor) { processors += processor }
    }
    val registry = TransformerPipeline()
    // Honor per-artifact package selection, as single-artifact `obfuscate` does: opt out of the
    // standard recipe and/or add provider packages. Any renamer they register is dropped below, since
    // renaming is global. The standard package keeps the shared keep set (preparedKeep = keep).
    val packages = (if (module.noDefaultPackage) emptyList() else listOf(StandardPackage(config, keep, null))) +
      TransformationPackages.select(module.packageIds, options)
    packages.forEach { it.configure(assembly, registry) }
    platform.extensions().filterIsInstance<PlatformPackages>()
      .flatMap { it.packages(context, options) }.forEach { it.configure(assembly, registry) }
    ReflectionScanner.scan(context, keep)
    val bodyPasses: List<Transformer> = registry.ordered().filterNot { it is RenamingTransformer }
    reports[module.stableId] = PipelineExecutor().execute(context, bodyPasses, excludedClasses, excludedResources, detailed = true)
    processors.forEach { it.beforeWrite(context) }
    outputProcessors[module.stableId] = processors.toList()
    applied.cleanup()
    frameRecompute[module.stableId] = bodyPasses.any { it.requiresFrameRecomputation }
    return roots
  }

  private val frameRecompute = LinkedHashMap<String, Boolean>()
  private val outputProcessors = LinkedHashMap<String, List<OutputProcessor>>()
}
