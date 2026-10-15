package com.peco2282.obfaryn.api

/** Immutable convenience implementation for platforms that do not need custom metadata behavior. */
class DefaultPlatformMetadata(
  override val id: String,
  override val displayName: String,
  override val implementationVersion: String,
  override val apiVersion: Int = PlatformApi.VERSION,
  override val description: String
) : PlatformMetadata {
  constructor(id: String, displayName: String, implementationVersion: String, apiVersion: Int = PlatformApi.VERSION) :
    this(id, displayName, implementationVersion, apiVersion, "$displayName ($id) [${implementationVersion}, API ${apiVersion}]")
  constructor(builder: PlatformMetadataBuilder) :
    this(builder.id, builder.displayName, builder.implementationVersion, builder.apiVersion, builder.description)
}

class MetadataBuilderImpl : PlatformMetadataBuilder {
  override var id: String = ""
  override var displayName: String = ""
  override var implementationVersion: String = ""
  override var apiVersion: Int = PlatformApi.VERSION
  override var description: String = ""

  fun build(): PlatformMetadata = DefaultPlatformMetadata(this)
}

/** Immutable convenience implementation of [PlatformDetection]. */
class DefaultPlatformDetection(
  override val confidence: Int,
  reasons: Iterable<String> = emptyList(),
) : PlatformDetection {
  init {
    require(confidence in 0..100) { "Platform detection confidence must be between 0 and 100" }
  }

  override val reasons: List<String> = reasons.toList()
}

/** Immutable map-backed options used by CLI, Gradle, and tests. */
class MapPlatformOptions(values: Map<String, String> = emptyMap()) : PlatformOptions {
  private val values = values.toMap()
  override val names: Set<String> get() = values.keys
  override fun get(name: String): String? = values[name]
}
