package com.peco2282.obfaryn.api

/**
 * Represents the default implementation of the `PlatformMetadata` interface.
 *
 * This class provides a standard way to encapsulate metadata details pertaining to a specific platform.
 * It includes properties such as a unique identifier, display name, implementation version, API version,
 * and a descriptive summary, adhering to the contract defined by the `PlatformMetadata` interface.
 *
 * @property id A unique identifier for the platform, typically a string in a canonical format.
 * @property displayName A human-readable name of the platform for display purposes.
 * @property implementationVersion The implementation-specific version of the platform.
 * @property apiVersion The API version supported by the platform, defaulting to the predefined platform constant.
 * @property description A detailed description that combines key metadata for human readability.
 *
 * @constructor Creates an instance of `DefaultPlatformMetadata` with specified metadata properties.
 * Secondary constructors allow initialization using either a preconfigured `PlatformMetadataBuilder` or
 * by providing specific values for required properties.
 */
class DefaultPlatformMetadata(
  override val id: String,
  override val displayName: String,
  override val implementationVersion: String,
  override val apiVersion: Int = PlatformApi.VERSION,
  override val description: String
) : PlatformMetadata {
  /**
   * Secondary constructor for `DefaultPlatformMetadata`.
   *
   * This constructor initializes a `DefaultPlatformMetadata` instance using the provided platform metadata properties.
   * The description is automatically generated based on the given `id`, `displayName`, `implementationVersion`, and
   * `apiVersion`, in the format: `"$displayName ($id) [${implementationVersion}, API ${apiVersion}]"`
   *
   * @param id A unique identifier for the platform.
   * @param displayName A human-readable name of the platform.
   * @param implementationVersion The version of the platform's implementation.
   * @param apiVersion The version of the supported platform API. Defaults to the constant `PlatformApi.VERSION`.
   */
  constructor(id: String, displayName: String, implementationVersion: String, apiVersion: Int = PlatformApi.VERSION) :
    this(id, displayName, implementationVersion, apiVersion, "$displayName ($id) [${implementationVersion}, API ${apiVersion}]")
  internal constructor(builder: PlatformMetadataBuilder) :
    this(builder.id, builder.displayName, builder.implementationVersion, builder.apiVersion, builder.description)
}

/**
 * Implementation of the `PlatformMetadataBuilder` interface for constructing
 * platform metadata objects.
 *
 * This class facilitates the creation of `PlatformMetadata` instances by
 * providing mutable properties for platform identification, versioning, and
 * descriptive details. It serves as a concrete builder for the platform metadata
 * configuration, adhering to the contract defined by the `PlatformMetadataBuilder`
 * interface.
 *
 * @see PlatformMetadataBuilder
 * @see PlatformMetadata
 * @see DefaultPlatformMetadata
 */
class MetadataBuilderImpl : PlatformMetadataBuilder {
  /**
   * A unique identifier for the platform metadata being constructed.
   *
   * This property represents a mutable string used to specify the unique
   * identifier associated with a particular platform metadata instance. It is
   * designed to be set during the metadata creation process and used as part of
   * the metadata representation.
   */
  override var id: String = ""

  /**
   * The human-readable name representing the platform metadata.
   *
   * This property allows specifying a descriptive display name for the platform metadata
   * being constructed. It is intended to provide a user-friendly identifier for the platform
   * details being configured.
   */
  override var displayName: String = ""

  /**
   * The implementation version of the platform metadata being constructed.
   *
   * This property represents a mutable string used to specify the version
   * of the implementation associated with the platform metadata. It is intended
   * to provide versioning details for the constructed platform metadata instance.
   */
  override var implementationVersion: String = ""

  /**
   * The API version associated with the platform metadata being constructed.
   *
   * This property specifies the version of the API that the platform metadata
   * conforms to. It is mutable, allowing the version to be configured during
   * the platform metadata construction process. By default, it is initialized
   * to the value defined by `PlatformApi.VERSION`.
   */
  override var apiVersion: Int = PlatformApi.VERSION

  /**
   * A detailed textual explanation or summary associated with the metadata being built.
   * This property can be modified to provide additional context or clarification
   * related to the metadata object being constructed.
   */
  override var description: String = ""

  fun build(): PlatformMetadata = let {
    check(id.isNotBlank()) { "Platform metadata ID must not be blank" }
    check(displayName.isNotBlank()) { "Platform metadata display name must not be blank" }
    check(implementationVersion.isNotBlank()) { "Platform metadata implementation version must not be blank" }
    check(apiVersion > 0) { "Platform metadata API version must be greater than 0" }
    check(description.isNotBlank()) { "Platform metadata description must not be blank" }
    DefaultPlatformMetadata(this)
  }
}

/**
 * Default implementation of the [PlatformDetection] interface.
 *
 * This class is responsible for defining the detection of a platform and determining
 * both the confidence level and the reasons supporting the detection. The confidence
 * is represented as an integer percentage between 0 and 100, while the reasons are
 * provided as a list of descriptive strings.
 *
 * @property confidence Confidence level for the platform detection, expressed as a percentage.
 * The value must be between 0 and 100 inclusive, where higher values indicate greater certainty.
 * An exception is thrown if the specified value does not fall within the valid range.
 *
 * @property reasons A list of strings that justify or explain the assessed confidence level.
 * These reasons provide context to the analysis and the final detection assessment.
 */
class DefaultPlatformDetection(
  override val confidence: Int,
  reasons: Iterable<String> = emptyList(),
) : PlatformDetection {
  init {
    require(confidence in 0..100) { "Platform detection confidence must be between 0 and 100" }
  }

  override val reasons: List<String> = reasons.toList()
}

/**
 * A concrete implementation of the `PlatformOptions` interface that utilizes a map
 * to represent a set of platform-specific configurable options.
 *
 * This class allows retrieval of key-value option pairs, where the keys are option names
 * and the values are their corresponding string representations.
 *
 * @constructor Creates a new instance of `MapPlatformOptions` with the specified map
 * of option keys and values. If no map is provided, an empty map is used by default.
 *
 * @param values A map representing the platform options, where the key is the option name
 * and the value is its corresponding string value.
 */
class MapPlatformOptions(values: Map<String, String> = emptyMap()) : PlatformOptions {
  private val values = values.toMap()
  override val names: Set<String> get() = values.keys
  override fun get(name: String): String? = values[name]
}
