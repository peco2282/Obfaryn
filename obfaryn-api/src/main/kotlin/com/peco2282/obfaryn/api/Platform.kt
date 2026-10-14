package com.peco2282.obfaryn.api

/**
 * Represents a target platform or environment supported by Obfaryn.
 *
 * Implementations of this interface define platform-specific behaviors, such as detecting whether
 * a project belongs to this platform, discovering entrypoint classes, configuring resource keep rules,
 * and creating platform-specific configurations.
 *
 * @param C The type of [PlatformConfiguration] associated with this platform.
 */
interface Platform<C : PlatformConfiguration> {
  /**
   * The unique identifier for this platform (e.g. `"generic"`, `"paper"`).
   */
  val id: String

  /**
   * Determines whether this platform is detected in the given [context].
   *
   * @param context The [PlatformContext] containing the classes and resources of the target application.
   * @return `true` if this platform is detected, `false` otherwise.
   */
  fun detect(context: PlatformContext): Boolean

  /**
   * Creates a new instance of the platform configuration [C].
   *
   * @return A new instance of [C].
   */
  fun createConfiguration(): C

  /**
   * Configures the platform settings on the provided [config] instance.
   *
   * @param config The [C] instance to configure.
   */
  fun configure(config: C)

  /**
   * Creates and configures a new [C] instance using the given configuration block.
   *
   * @param config A configuration lambda applied to the created [C] instance.
   * @return The configured [C] instance.
   */
  fun configure(config: C.() -> Unit): C = createConfiguration().also {
    configure(it)
    it.config()
  }

  /**
   * Configures platform-specific resource rules based on the provided [context].
   *
   * @param context The [PlatformContext] containing classes and resources.
   * @param config The [PlatformConfiguration] to which resource rules are added.
   */
  fun configureResources(context: PlatformContext, config: PlatformConfiguration) = Unit

  /**
   * Discovers and returns the entrypoint classes for this platform from the provided [context].
   *
   * Entrypoints are classes (such as main classes, plugin entrypoints, or event listeners)
   * that must be preserved during obfuscation.
   *
   * @param context The [PlatformContext] containing classes and resources.
   * @return A set of internal class names representing entrypoints.
   */
  fun entrypoints(context: PlatformContext): Set<String> = emptySet()

  /**
   * Applies this platform's configuration and resource rules to the given [target] configuration.
   *
   * @param context The [PlatformContext] containing classes and resources.
   * @param target The target [PlatformConfiguration] to apply settings to.
   */
  fun applyConfiguration(context: PlatformContext, target: PlatformConfiguration) {
    val config = createConfiguration()
    configure(config)
    config.applyTo(target)
    configureResources(context, target)
  }
}