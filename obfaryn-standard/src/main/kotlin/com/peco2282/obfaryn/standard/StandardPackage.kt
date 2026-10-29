package com.peco2282.obfaryn.standard

import com.peco2282.obfaryn.api.assembly.*
import com.peco2282.obfaryn.api.transformer.*
import com.peco2282.obfaryn.api.protection.ControlFlowMode
import com.peco2282.obfaryn.core.*
import com.peco2282.obfaryn.core.keep.KeepRules
import com.peco2282.obfaryn.core.rename.RenameTransformer
import com.peco2282.obfaryn.core.transformer.*
import com.peco2282.obfaryn.transformer.*
import com.peco2282.obfaryn.transformer.arithmetic.IntegerArithmeticTransformer
import com.peco2282.obfaryn.transformer.linkage.PrelinkRuntimeTransformer

/** Optional standard recipe; users may replace it entirely with their own API-only recipe. */
class StandardPackage internal constructor(
  private val config: ObfuscationConfig,
  private val preparedKeep: KeepRules?,
  private val selectedVirtualization: Boolean?,
) : TransformationPackage {
  constructor(config: ObfuscationConfig = ObfuscationConfig()) : this(config, null, null)
  override fun configure(context: AssemblyContext, registry: TransformerRegistry) {
    require(!config.prelinkCalls || config.callIndirection) { "prelinkCalls requires callIndirection" }
    val keep = preparedKeep ?: KeepRules().also {
      context.configuration.applyTo(it)
      config.keep.forEach(it::add)
    }
    require((config.integrityPrivateKey == null) == (config.integrityPublicKey == null)) {
      "Both integrityPrivateKey and integrityPublicKey are required"
    }
    require(!config.virtualizationAnalyzeOnly && config.virtualizationSelectionMode != VirtualizationSelectionMode.ANALYZE_ONLY) {
      "Use the configured Obfuscator facade for analysis-only execution"
    }
    val random = context.random
    val input = context.context
    val exclusions = keep.virtualizationExclusions()
    val strings = StringEncryptionTransformer(config.stringRandom ?: random, config.stringEncryptionStrategy)
    val numbers = NumberObfuscationTransformer(config.numberRandom ?: random, config.numberStrategy, config.numberSelector)
    val hasScope = context.applicationPackages.isNotEmpty()
    require(!(config.protectionLevel == ProtectionLevel.STRONG || config.callIndirection || config.integerArithmetic) || hasScope) {
      "Strong protection, call indirection and integer arithmetic require application scope"
    }
    val excluded = input.classes.keys.filterTo(linkedSetOf()) { name ->
      config.excludeClasses.any { Regex(it).matches(name) } ||
        com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy.noObfuscation(input.classes.getValue(name)) ||
        (config.protectionLevel == ProtectionLevel.STRONG && !input.transformationInputs.isApplicationClass(name)) ||
        (config.prelinkCalls && name == PrelinkRuntimeTransformer.RUNTIME_NAME)
    }
    excluded.forEach { context.excludeClass(it); keep.keepClass(it) }
    val analysis = if (selectedVirtualization == null) VirtualizationAnalyzer(
      config.virtualizeMethods, config.excludeVirtualization, exclusions).analyze(input.classes.values.filter { it.name !in excluded }) else null
    analysis?.requireEligible(config.virtualizationSelectionMode)
    val virtualizationRequested = selectedVirtualization ?: (analysis!!.selected > 0)

    for (name in input.resources.keys) if (config.excludeResources.any { Regex(it).matches(name) }) context.excludeResource(name)
    if (hasScope && !config.stripKotlinMetadata && config.kotlinMetadataMode != KotlinMetadataMode.STRIP)
      input.classes.keys.filterNot { input.transformationInputs.isApplicationClass(it) }.forEach(keep::keepClass)
    for (name in input.classes.keys) if (config.excludeRenaming.any { Regex(it).matches(name) }) keep.keepClass(name)
    config.integrityPrivateKey?.let { privateKey ->
      context.protectInput(privateKey)
      context.protectInput(config.integrityPublicKey!!)
      val integrity = com.peco2282.obfaryn.core.integrity.IntegrityProtection(privateKey, config.integrityPublicKey)
      context.outputProcessor(object : OutputProcessor {
        override fun beforeWrite(context: com.peco2282.obfaryn.api.ObfuscationContext) { integrity.inject(context, excluded, random) }
        override fun afterWrite(output: java.nio.file.Path) { integrity.seal(output, context.reproducible) }
      })
    }
    if (preparedKeep == null) keep.applyTo(context.configuration)
    val rename = RenameTransformer(config.copy(classpath = context.classpath), keep)
    if (preparedKeep == null) rename.warnings += ReflectionScanner.scan(input, keep)
    val passes = listOfNotNull(
      VirtualizationTransformer(config.virtualizeMethods, random, config.virtualizationRuntimeGroupSize,
        config.virtualizationSelectionMode, context.classpath + context.input, config.excludeVirtualization, exclusions)
        .takeIf { virtualizationRequested },
      MetadataStripTransformer(stripDebugInfo = config.stripDebugInfo,
        stripKotlinMetadata = config.stripKotlinMetadata || config.kotlinMetadataMode == KotlinMetadataMode.STRIP,
        stripGenericSignatures = config.stripGenericSignatures, stripMethodParameters = config.stripMethodParameters)
        .takeIf { config.stripDebugInfo || config.stripKotlinMetadata || config.kotlinMetadataMode == KotlinMetadataMode.STRIP ||
          config.stripGenericSignatures || config.stripMethodParameters },
      ConstantFieldTransformer(strings = config.stringEncryption && config.stringConstantFields,
        numbers = config.numberObfuscation && config.numberConstantFields).takeIf { hasScope &&
        (config.stringEncryption && config.stringConstantFields || config.numberObfuscation && config.numberConstantFields) },
      strings.takeIf { config.stringEncryption },
      numbers.takeIf { config.numberObfuscation },
      IntegerArithmeticTransformer(random, config.maxArithmeticOperationsPerMethod).takeIf { config.integerArithmetic },
      ControlFlowTransformer(random, config.effectiveControlFlowMode == ControlFlowMode.LIGHT)
        .takeIf { config.effectiveControlFlowMode != ControlFlowMode.NONE },
      rename.takeIf { config.renameClasses || config.renameMethods || config.renameFields },
      CallIndirectionTransformer(random, config.prelinkCalls) { name -> input.transformationInputs.isApplicationClass(name) }
        .takeIf { config.callIndirection },
      PrelinkRuntimeTransformer().takeIf { config.prelinkCalls },
    )
    passes.forEach(registry::add)
  }
}

/** Discovered only when explicitly selected by name. Default frontend configuration is supplied separately. */
class StandardPackageProvider : TransformationPackageProvider {
  override val id = "standard"
  override fun create(options: com.peco2282.obfaryn.api.PlatformOptions) = StandardPackage()
}
