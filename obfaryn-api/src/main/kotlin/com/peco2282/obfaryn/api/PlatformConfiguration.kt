package com.peco2282.obfaryn.api

/**
 * Configuration interface for specifying obfuscation exclusion (keep) rules.
 *
 * `PlatformConfiguration` provides a fluent API for platforms and plugins to define which classes,
 * members (methods and fields), packages, and resources should be preserved from obfuscation.
 */
interface PlatformConfiguration {
  /**
   * Applies the keep rules defined in this configuration to the specified [target] configuration.
   *
   * @param target The destination configuration to copy the keep rules into.
   */
  fun applyTo(target: PlatformConfiguration)

  /**
   * Preserves the specified class and all its members (methods and fields) from obfuscation.
   *
   * @param name The fully qualified class name in dot format (e.g., `com.example.MyClass`)
   *             or internal binary format (e.g., `com/example/MyClass`).
   * @return This configuration instance for method chaining.
   */
  fun keepClass(name: String): PlatformConfiguration

  /**
   * Preserves only the class name from obfuscation, allowing its members to be obfuscated.
   *
   * Default implementation delegates to [keepClass].
   *
   * @param name The fully qualified class name in dot format (e.g., `com.example.MyClass`)
   *             or internal binary format (e.g., `com/example/MyClass`).
   * @return This configuration instance for method chaining.
   */
  fun keepClassNameOnly(name: String): PlatformConfiguration = keepClass(name)

  /**
   * Preserves all classes and their members within the specified package from obfuscation.
   *
   * @param name The package name in dot format (e.g., `com.example.pkg`) or slash format (e.g., `com/example/pkg`).
   * @return This configuration instance for method chaining.
   */
  fun keepPackage(name: String): PlatformConfiguration

  /**
   * Preserves the specified resource file from being obfuscated or renamed.
   *
   * @param name The path or name of the resource to keep (e.g., `plugin.yml`).
   * @return This configuration instance for method chaining.
   */
  fun keepResource(name: String): PlatformConfiguration

  /**
   * Preserves a specific method from obfuscation.
   *
   * @param owner The internal binary or dot-separated name of the class that declares the method.
   * @param name The name of the method to keep.
   * @param descriptor The JVM bytecode descriptor of the method (e.g., `()V` or `(Ljava/lang/String;)I`).
   * @return This configuration instance for method chaining.
   */
  fun keepMethod(owner: String, name: String, descriptor: String): PlatformConfiguration

  /**
   * Preserves a specific field from obfuscation.
   *
   * @param owner The internal binary or dot-separated name of the class that declares the field.
   * @param name The name of the field to keep.
   * @return This configuration instance for method chaining.
   */
  fun keepField(owner: String, name: String): PlatformConfiguration

  /**
   * Keeps a method as normal JVM bytecode while allowing all other enabled obfuscation passes.
   * Platforms use this for latency-sensitive callbacks that should not pay interpreter overhead.
   */
  fun excludeMethodFromVirtualization(
    owner: String,
    name: String,
    descriptor: String,
    reason: String = "platform performance policy",
  ): PlatformConfiguration = this
}
