package com.peco2282.obfaryn.api.assembly

import com.peco2282.obfaryn.api.*
import com.peco2282.obfaryn.api.transformer.TransformerRegistry
import java.nio.file.Path
import java.util.Random

/** A reusable recipe. Construct stateful passes inside configure, once per run. */
fun interface TransformationPackage {
  fun configure(context: AssemblyContext, registry: TransformerRegistry)
}

/** Host services available while assembling a fresh run; no standard implementation is required. */
interface AssemblyContext {
  val input: Path
  val output: Path
  val reproducible: Boolean
  /** Protect a dependency/key from being overwritten by the host's artifact destinations. */
  fun protectInput(path: Path)
  /** Hide an entry from body passes and preserve its name. Direct lookups remain available. */
  fun excludeClass(internalName: String)
  fun excludeResource(path: String)
  val context: ObfuscationContext
  val configuration: PlatformConfiguration
  val options: PlatformOptions
  /** Original internal package roots used by the host for application scope. */
  val applicationPackages: List<String>
  val classpath: List<Path>
  val random: Random
  /** Registers output work in package order. afterWrite is never called for dry runs. */
  fun outputProcessor(processor: OutputProcessor)
}

/** Optional archive lifecycle work, supplied by packages rather than hard-coded in the engine. */
interface OutputProcessor {
  fun beforeWrite(context: ObfuscationContext) = Unit
  fun afterWrite(output: Path) = Unit
}

/** ServiceLoader entry point. Merely installing a provider never selects its package. */
interface TransformationPackageProvider {
  val id: String
  fun create(options: PlatformOptions): TransformationPackage
}

/** Platform-specific recipes use the same registry and contracts as user-supplied recipes. */
fun interface PlatformPackages : PlatformExtension {
  fun packages(context: PlatformContext, options: PlatformOptions): List<TransformationPackage>
}
