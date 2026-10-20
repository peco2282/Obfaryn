package com.peco2282.obfaryn.api

/**
 * Represents a configurable and extensible platform used for processing target inputs.
 *
 * The `Platform` interface provides the structure for detecting platform-specific inputs,
 * discovering entrypoints, and applying configuration rules for obfuscation or transformation.
 * It operates within a given context, utilizes platform options, and outputs configurations
 * tailored to its detected environment.
 *
 * Implement [PlatformProvider] to expose a platform to the host. Use [configure] for keep rules
 * and [extensions] for annotation policies or generated classes, resources, and archives.
 *
 * A minimal descriptor-based implementation can keep a reflectively loaded entrypoint:
 * ```kotlin
 * class ExamplePlatform : Platform {
 *   override val metadata = PlatformProvider.metadata {
 *     id = "example"
 *     displayName = "Example framework"
 *     implementationVersion = "1.0.0"
 *     description = "Support for example-plugin.yml applications"
 *   }
 *
 *   override fun detect(context: PlatformContext): PlatformDetection =
 *     if ("example-plugin.yml" in context.resources) {
 *       DefaultPlatformDetection(100, listOf("Found example-plugin.yml"))
 *     } else {
 *       DefaultPlatformDetection(0)
 *     }
 *
 *   override fun configure(
 *     context: PlatformContext,
 *     options: PlatformOptions,
 *     target: PlatformConfiguration,
 *   ) {
 *     target.keepClass(options.require("main-class"))
 *     target.keepResource("example-plugin.yml")
 *   }
 * }
 * ```
 * This example requires the caller to supply `main-class`; detecting a descriptor does not
 * automatically parse it or populate options. Detection confidence is evidence, not a keep rule.
 */
interface Platform {
  /**
   * Metadata providing identity and compatibility information for a platform.
   *
   * This includes details such as the platform's unique identifier, display name,
   * implementation version, API version, and a description of the platform. The metadata
   * is used to provide context about the platform before it is fully instantiated and
   * facilitates configuration and compatibility validation.
   */
  val metadata: PlatformMetadata

  /**
   * The unique identifier for the platform.
   *
   * This value is derived from the metadata of the platform and uniquely distinguishes
   * it from other platforms. It serves as a reference for identifying and working
   * with the platform in various configurations and operations.
   */
  val id: String get() = metadata.id

  /**
   * Detects the platform environment based on the provided execution context.
   *
   * This method analyzes the given [PlatformContext] to determine the suitability of the
   * platform, returning a detection result that includes confidence and reasons for the match.
   *
   * @param context The execution environment context containing all loaded classes and resources.
   * @return A [PlatformDetection] instance containing the detection confidence level and rationale.
   */
  fun detect(context: PlatformContext): PlatformDetection

  /**
   * Extracts the set of entrypoints from the provided platform context and options.
   *
   * This method identifies the key entrypoints in the execution environment, which
   * can serve as starting points for further analysis or platform-specific processing.
   *
   * @param context The execution environment context containing all loaded classes and resources.
   * @param options The platform options providing additional parameters or configurations.
   * @return Entrypoint class names used to identify application code, or an empty set if none are found.
   * Prefer JVM internal names such as `com/example/Main`, not method names or class descriptors.
   */
  fun entrypoints(context: PlatformContext, options: PlatformOptions): Set<String> = emptySet()

  /**
   * Configures the platform-specific rules and settings based on the provided context and options.
   *
   * This method customizes the [PlatformConfiguration] for the target platform, using details
   * from the execution environment and additional configuration options.
   * The default implementation leaves [target] unchanged. Add rules through [target] rather than
   * modifying the class nodes exposed by [context].
   *
   * @param context The execution environment context containing all loaded classes and resources.
   * @param options The platform options providing additional parameters or configurations.
   * @param target The configuration to be modified with platform-specific rules and exclusions.
   */
  fun configure(context: PlatformContext, options: PlatformOptions, target: PlatformConfiguration) = Unit

  /**
   * Retrieves a collection of platform extensions associated with the platform.
   *
   * Platform extensions provide additional functionalities or behaviors that
   * can be integrated into the platform for specific customization or enhancement.
   * Return implementations of the supported extension interfaces, such as [AnnotationPolicyProvider]
   * or [ResourceContributor]; implementing the marker [PlatformExtension] alone adds no behavior.
   *
   * @return A collection of [PlatformExtension] instances, or an empty collection if no extensions are present.
   */
  fun extensions(): Collection<PlatformExtension> = emptyList()
}

/**
 * Builder interface for constructing platform metadata objects.
 *
 * This interface is intended to be used for creating implementations of platform metadata
 * with configurable properties that describe a platform's characteristics.
 *
 * @property id A unique identifier for the platform, such as `paper` or `com.example.platform`.
 * @property displayName A human-readable name for the platform.
 * @property implementationVersion The platform's implementation version, represented as a string.
 * @property apiVersion The API version of the platform. Determines compatibility with specific platform capabilities.
 * @property description A detailed description of the platform, which may include its purpose or key features.
 */
interface PlatformMetadataBuilder {
  /**
   * A unique identifier for the platform.
   *
   * Used for platform selection and lookup. It may be changed while building metadata;
   * the resulting [PlatformMetadata.id] is read-only. Choose a stable, non-blank value.
   */
  var id: String

  /**
   * A human-readable name for the platform.
   *
   * This property represents a descriptive name for the platform, meant to be easily
   * understood by users or developers. Unlike the platform's unique identifier (`id`),
   * this name is not guaranteed to be unique and may be used for display purposes, logging,
   * or UI presentation. It is typically assigned during the creation or configuration of
   * a platform metadata object.
   */
  var displayName: String

  /**
   * Represents the implementation version of the platform.
   *
   * This property holds a version string that describes the specific implementation
   * of the platform or software it represents. It is often used to distinguish between
   * different releases or builds of the platform, allowing precise identification of
   * the deployed version.
   *
   * Typical versioning schemes may include semantic versioning (e.g., "1.0.0") or
   * custom version formats defined by the platform's development practices.
   *
   * This value can be utilized in logging, debugging, or compatibility checks.
   */
  var implementationVersion: String

  /**
   * Represents the API version associated with the platform metadata being constructed.
   *
   * This value defines the compatibility level of the platform, typically adhering to a
   * predefined constant value (e.g., `PlatformApi.VERSION`). It serves as an indicator of
   * the feature set and functionality supported by the platform implementation.
   *
   * The version is expected to influence various operations, including compatibility checks,
   * feature toggling, and metadata validation during platform initialization or transformations.
   *
   * @see PlatformApi.VERSION
   */
  var apiVersion: Int

  /**
   * Represents a human-readable description of the platform or metadata being built.
   *
   * Provides additional context or details about the platform, often derived from other properties
   * such as `displayName`, `id`, `implementationVersion`, and `apiVersion`.
   *
   * Set a non-blank value when using [PlatformProvider.metadata]; that builder does not
   * generate a description automatically.
   */
  var description: String
}

/**
 * Represents metadata associated with a platform.
 *
 * This interface defines essential properties that provide identification, versioning,
 * and descriptive details for a platform. It is intended to be implemented or extended
 * by platform-specific metadata representations.
 */
interface PlatformMetadata {
  /**
   * A unique identifier representing a specific platform.
   *
   * The `id` is used to distinguish platforms in a consistent manner,
   * typically following a canonical naming convention. It serves
   * as the primary reference for identifying platform metadata.
   */
  val id: String

  /**
   * The human-readable name of the platform.
   *
   * This property provides a descriptive name associated with the platform that can
   * be displayed in user interfaces or logs. Unlike the `id`, which is typically
   * a unique identifier, the `displayName` serves as a more user-friendly label.
   */
  val displayName: String

  /**
   * Specifies the implementation version of the platform.
   *
   * This property represents the version of the platform's implementation, typically
   * used to identify its build or release version. It complements the `apiVersion`
   * by providing a more granular versioning scheme that reflects changes or updates
   * specific to the implementation.
   */
  val implementationVersion: String

  /**
   * Specifies the supported API version for a platform.
   *
   * This property indicates the version of the platform's API that the implementation adheres to.
   * It serves as a compatibility guideline for integrations or extensions that rely on the platform.
   * Platform developers use this to signal compatibility across different API versions.
   */
  val apiVersion: Int

  /**
   * A detailed description of the platform.
   *
   * This property provides additional contextual or descriptive information about the platform,
   * often including a summary of its characteristics, purpose, or usage. It may combine
   * information from other properties such as the platform's identifier, display name,
   * implementation version, and supported API version to form a human-readable narrative.
   */
  val description: String
}

/**
 * Represents the mechanism for detecting a platform and assessing its suitability.
 *
 * Platform detection typically involves analyzing the execution environment or
 * target input to determine if and how well a specific platform applies.
 *
 * Confidence levels and reasoning are used to determine detection certainty.
 */
interface PlatformDetection {
  /**
   * Represents the confidence level for platform detection, expressed as a percentage.
   *
   * A value between 0 and 100 (inclusive) that indicates the certainty of a platform's
   * suitability based on the detection process. Higher values indicate greater confidence.
   *
   * A confidence of `0` means no match; any positive value makes [detected] true.
   * [DefaultPlatformDetection] rejects values outside this range.
   */
  val confidence: Int

  /**
   * Represents the list of reasons explaining the assessment of platform suitability.
   *
   * Each reason describes evidence behind [confidence], such as the presence of a platform descriptor.
   * An empty list is valid when no explanatory evidence is available.
   */
  val reasons: List<String>

  /**
   * Indicates whether a platform has been detected with a non-zero confidence level.
   *
   * The detection is considered successful if the confidence level exceeds 0, meaning
   * there is some certainty about the platform's suitability based on the detection process.
   */
  val detected: Boolean get() = confidence > 0
}

/**
 * Represents a set of configurable platform options used by various platform implementations.
 *
 * This interface provides methods for retrieving option values, enforcing required options,
 * and handling boolean options with support for default values.
 * Options are strings; the consuming platform must validate domain-specific constraints:
 * ```kotlin
 * val options: PlatformOptions = MapPlatformOptions(mapOf("workers" to "4"))
 * val workers = options.integer("workers", 1)
 * require(workers > 0) { "workers must be positive" }
 * val generateDescriptor = options.boolean("generate-descriptor", false)
 * ```
 * Defaults handle absent keys, not malformed supplied values. Unknown keys are not rejected
 * by this interface; use [names] if the platform needs to validate its supported option set.
 */
interface PlatformOptions {
  /**
   * Represents the set of option names available in the platform options.
   *
   * Lists supplied keys, not necessarily every key supported by a platform implementation.
   * Option names and their interpretation are defined by the consuming platform.
   */
  val names: Set<String>

  /**
   * Retrieves the value associated with the specified option name.
   *
   * @param name The name of the option to retrieve.
   * @return The value of the option if it exists, or `null` if the option is not present.
   */
  operator fun get(name: String): String?

  /**
   * Retrieves the value of the specified option, enforcing that the option must exist.
   * If the option is missing, an error is thrown with a message indicating the missing option.
   * An empty string is considered present and is returned unchanged.
   *
   * @param name The name of the required option.
   * @return The value of the required option as a string.
   * @throws IllegalStateException If the option with the specified name is not present.
   */
  fun require(name: String): String = get(name) ?: error("Missing platform option: $name")

  /**
   * Retrieves a boolean option, using [default] only when the option is absent.
   * Values must be `true` or `false`, ignoring case; whitespace is not trimmed.
   *
   * @param name The name of the option to retrieve.
   * @param default The default boolean value to return if the option is not present.
   * @return The parsed value, or [default] when the option is absent.
   * @throws IllegalStateException If the option value is not "true", "false", or null.
   */
  fun boolean(name: String, default: Boolean): Boolean = get(name)?.let {
    when (it.lowercase()) {
      "true" -> true
      "false" -> false
      else -> error("Platform option '$name' must be true or false")
    }
  } ?: default

  /**
   * Retrieves a decimal integer option, using [default] only when the option is absent.
   *
   * Parsing follows [String.toInt]: an optional sign is accepted, but whitespace, non-decimal
   * values, and values outside the [Int] range are rejected. No platform-specific bounds are checked.
   *
   * @param name The option name to look up.
   * @param default The value returned when no option with [name] exists.
   * @return The parsed value, or [default] when the option is absent.
   * @throws IllegalStateException If a supplied value cannot be parsed as an [Int].
   */
  fun integer(name: String, default: Int): Int = get(name)?.let {
    try {
      it.toInt()
    } catch (_: NumberFormatException) {
      error("Platform option '$name' must be an integer")
    }
  } ?: default
}

/**
 * Provides an interface for managing platform-specific functionality and metadata.
 *
 * The `PlatformProvider` is responsible for instantiating platforms and exposing
 * metadata that describes the platform's identity, versioning, and compatibility.
 * It serves as a factory and metadata accessor for platform-related operations.
 * Keep [metadata] consistent with the metadata exposed by the platform returned from [create].
 * For a platform class named `ExamplePlatform`, a provider can be implemented as follows:
 * ```kotlin
 * class ExamplePlatformProvider : PlatformProvider {
 *   override val metadata: PlatformMetadata = ExamplePlatform().metadata
 *   override fun create(): Platform = ExamplePlatform()
 * }
 * ```
 * A factory implementation alone does not register the provider with a host; distribution
 * must also supply the discovery or registration mechanism expected by that host.
 */
interface PlatformProvider {
  /**
   * Represents metadata associated with the platform managed by the implementation.
   *
   * Provides platform-specific details such as its unique identifier, display name,
   * implementation version, API compatibility, and a human-readable description.
   * This metadata is integral to identifying and describing the platform in a consistent,
   * user-friendly, and version-aware manner across various implementations.
   */
  val metadata: PlatformMetadata

  /**
   * Creates and returns a new instance of the platform.
   *
   * This method serves as a factory for creating platforms that conform to the [Platform] interface.
   * It utilizes the metadata and configuration specified in the implementing class to produce a
   * fully initialized platform instance. The returned platform can later facilitate environment
   * detection, entrypoint discovery, configuration, and extension retrieval.
   *
   * @return A new [Platform] instance that is ready to be used for platform-specific operations.
   */
  fun create(): Platform

  companion object {
    /**
     * Constructs a new instance of platform metadata using the provided builder configuration.
     *
     * This function allows the caller to define platform metadata properties
     * such as the identifier, display name, implementation version, API version,
     * and description using a lambda configuration block. The resulting metadata
     * can be used to describe platform-specific characteristics and compatibility details.
     * Set `id`, `displayName`, `implementationVersion`, and `description` to non-blank strings.
     * `apiVersion` defaults to [PlatformApi.VERSION] and must be positive.
     *
     * @param builder A lambda function used to configure the platform metadata.
     *                The builder provides mutable properties for setting
     *                the platform's identifier, display name, versioning details,
     *                and description.
     * @return A new instance of [PlatformMetadata] containing the configured values.
     * @throws IllegalStateException If a required string is blank or the API version is not positive.
     */
    @JvmStatic
    fun metadata(builder: PlatformMetadataBuilder.() -> Unit): PlatformMetadata = MetadataBuilderImpl().apply(builder).build()
  }
}

/**
 * Provides core functionality and constants for platform APIs.
 *
 * This object serves as a centralized configuration for platform-specific logic and properties
 * used in the API ecosystem. For example, it defines the API version compatibility to ensure
 * consistency across implementations and extensions.
 *
 * Constants and definitions within this object are typically referenced by platform metadata
 * implementations, extensions, and core systems to maintain alignment with API standards.
 */
object PlatformApi {
  /**
   * Defines the current API version used across the platform.
   *
   * This value is utilized as a reference for compatibility checks, ensuring that
   * different components within the platform interact using a consistent API version.
   * It serves both as a default for versioning in various platform constructs
   * and as a baseline for runtime validation.
   *
   * Changes to this value should be made cautiously to preserve backward compatibility
   * and avoid breaking existing integrations.
   */
  const val VERSION: Int = 1
}
