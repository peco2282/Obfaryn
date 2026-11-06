package com.peco2282.obfaryn.api.assembly

import com.peco2282.obfaryn.api.*
import com.peco2282.obfaryn.api.transformer.TransformerRegistry
import java.nio.file.Path
import java.util.Random

/**
 * A reusable recipe. Construct stateful passes inside [configure], once per run; a package must
 * not cache state across runs, since the same instance may be reused by a host across them.
 */
fun interface TransformationPackage {
  /** Registers this package's passes against [registry] using services exposed by [context]. */
  fun configure(context: AssemblyContext, registry: TransformerRegistry)
}

/** Host services available while assembling a fresh run; no standard implementation is required. */
interface AssemblyContext {
  /** The artifact being transformed. */
  val input: Path

  /** Destination for the transformed artifact. */
  val output: Path

  /** Whether the host requested a byte-for-byte reproducible output for identical inputs/options. */
  val reproducible: Boolean

  /** Protect a dependency/key from being overwritten by the host's artifact destinations. */
  fun protectInput(path: Path)

  /** Hide an entry from body passes and preserve its name. Direct lookups remain available. */
  fun excludeClass(internalName: String)

  /** Hide a resource entry from resource-rewriting passes. */
  fun excludeResource(path: String)

  /** Shared run-wide obfuscation state (renaming tables, secrets, reporting). */
  val context: ObfuscationContext

  /** Effective platform configuration for this run. */
  val configuration: PlatformConfiguration

  /** Effective platform options for this run. */
  val options: PlatformOptions

  /** Original internal package roots used by the host for application scope. */
  val applicationPackages: List<String>

  /** Resolved compile/runtime classpath available while assembling passes. */
  val classpath: List<Path>

  /** Run-scoped random source; use this instead of a fresh [Random] for reproducibility. */
  val random: Random

  /** Registers output work in package order. afterWrite is never called for dry runs. */
  fun outputProcessor(processor: OutputProcessor)
}

/** Optional archive lifecycle work, supplied by packages rather than hard-coded in the engine. */
interface OutputProcessor {
  /** Runs before any entry is written to the output archive. */
  fun beforeWrite(context: ObfuscationContext) = Unit

  /** Runs after the output archive at [output] has been fully written; never called for dry runs. */
  fun afterWrite(output: Path) = Unit
}

/** ServiceLoader entry point. Merely installing a provider never selects its package. */
interface TransformationPackageProvider {
  /** Stable identifier used to select this provider explicitly, such as via CLI/Gradle configuration. */
  val id: String

  /** Creates a fresh [TransformationPackage] instance configured from [options]. */
  fun create(options: PlatformOptions): TransformationPackage
}

/** Platform-specific recipes use the same registry and contracts as user-supplied recipes. */
fun interface PlatformPackages : PlatformExtension {
  /** The platform's own recipes to run for this [context]/[options], in order. */
  fun packages(context: PlatformContext, options: PlatformOptions): List<TransformationPackage>
}
