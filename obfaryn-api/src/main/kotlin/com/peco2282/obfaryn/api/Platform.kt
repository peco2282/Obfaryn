package com.peco2282.obfaryn.api

/**
 * A platform-neutral policy adapter for an application, framework, runtime, or private convention.
 * Implementations inspect the input and contribute rules; they do not transform bytecode directly.
 */
interface Platform {
  val metadata: PlatformMetadata
  val id: String get() = metadata.id

  /** Returns how confidently this platform recognizes the input and why. */
  fun detect(context: PlatformContext): PlatformDetection

  /** Discovers externally referenced or lifecycle entrypoint classes as JVM internal names. */
  fun entrypoints(context: PlatformContext, options: PlatformOptions): Set<String> = emptySet()

  /** Adds keep, resource, reflection, and performance rules for this input. */
  fun configure(context: PlatformContext, options: PlatformOptions, target: PlatformConfiguration) = Unit
}

interface PlatformMetadataBuilder {
  var id: String
  var displayName: String
  var implementationVersion: String
  var apiVersion: Int
  var description: String
}

/** Identity and compatibility information available before a platform is instantiated. */
interface PlatformMetadata {
  val id: String
  val displayName: String
  val implementationVersion: String
  val apiVersion: Int
  val description: String
}

/** A scored auto-detection result. A confidence of zero means no match. */
interface PlatformDetection {
  val confidence: Int
  val reasons: List<String>
  val detected: Boolean get() = confidence > 0
}

/** Generic settings let custom platforms expose options without depending on a frontend. */
interface PlatformOptions {
  val names: Set<String>
  operator fun get(name: String): String?

  fun require(name: String): String = get(name) ?: error("Missing platform option: $name")
  fun boolean(name: String, default: Boolean): Boolean = get(name)?.let {
    when (it.lowercase()) {
      "true" -> true
      "false" -> false
      else -> error("Platform option '$name' must be true or false")
    }
  } ?: default
}

/** Service-provider entrypoint. A new platform is created for each obfuscation run. */
interface PlatformProvider {
  val metadata: PlatformMetadata
  fun create(): Platform

  companion object {
    fun metadata(builder: PlatformMetadataBuilder.() -> Unit): PlatformMetadata = MetadataBuilderImpl().apply(builder).build()
  }
}

/** Compatibility level checked when external providers are loaded. */
object PlatformApi {
  const val VERSION: Int = 1
}
