package com.peco2282.obfaryn.api

/**
 * Configuration interface for specifying obfuscation exclusion (keep) rules.
 *
 * `PlatformConfiguration` provides a fluent API for platforms and plugins to define which classes,
 * members (methods and fields), packages, and resources should be preserved from obfuscation.
 * Class and owner names may use dotted or JVM internal form; method descriptors must use JVM
 * descriptor syntax. Keep rules describe preservation requirements, not byte-for-byte output guarantees:
 * references may still need updating when other declarations are renamed.
 * In the core implementation, keep rules preserve names but do not disable method-body passes
 * or virtualization. Keeping fields also prevents their constant values from being moved into
 * class initialization. Use [excludeMethodFromVirtualization] for virtualization-specific exclusions.
 */
interface PlatformConfiguration {
  /**
   * Applies the keep rules defined in this configuration to the specified [target] configuration.
   *
   * @param target The destination configuration to copy the keep rules into.
   */
  fun applyTo(target: PlatformConfiguration)

  /**
   * Preserves the name of the specified class and the names of all its methods and fields.
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
   * Implementations must override this method to provide name-only preservation; otherwise
   * members receive the broader protection of [keepClass] as a compatibility fallback.
   *
   * @param name The fully qualified class name in dot format (e.g., `com.example.MyClass`)
   *             or internal binary format (e.g., `com/example/MyClass`).
   * @return This configuration instance for method chaining.
   */
  fun keepClassNameOnly(name: String): PlatformConfiguration = keepClass(name)

  /**
   * Preserves class and member names within the specified package.
   * The core implementation includes subpackages as well as the named package itself.
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
   * Preserves the name of a specific method, identified by owner, name, and descriptor.
   * This does not automatically preserve the owner class name or suppress body transformations.
   *
   * @param owner The internal binary or dot-separated name of the class that declares the method.
   * @param name The name of the method to keep.
   * @param descriptor The JVM bytecode descriptor of the method (e.g., `()V` or `(Ljava/lang/String;)I`).
   * @return This configuration instance for method chaining.
   */
  fun keepMethod(owner: String, name: String, descriptor: String): PlatformConfiguration

  /**
   * Preserves a specific field's name without automatically preserving its owner class name.
   *
   * @param owner The internal binary or dot-separated name of the class that declares the field.
   * @param name The name of the field to keep.
   * @return This configuration instance for method chaining.
   */
  fun keepField(owner: String, name: String): PlatformConfiguration

  /**
   * Keeps a method as normal JVM bytecode while allowing all other enabled obfuscation passes.
   * Platforms use this for latency-sensitive callbacks that should not pay interpreter overhead.
   *
   * The default implementation is a no-op for configurations that do not support virtualization
   * policy. This rule does not preserve the method's name or exclude other body transformations.
   * In the core implementation, an explicit virtualization annotation can override this platform
   * exclusion; user-configured virtualization exclusion patterns still take precedence.
   *
   * @param owner The declaring class name in dotted or JVM internal form.
   * @param name The exact method name, without a descriptor.
   * @param descriptor The JVM method descriptor, such as `(I)V`, identifying the overload.
   * @param reason Human-readable explanation for diagnostics; defaults to a platform performance policy.
   * @return This configuration instance for method chaining.
   */
  fun excludeMethodFromVirtualization(
    owner: String,
    name: String,
    descriptor: String,
    reason: String = "platform performance policy",
  ): PlatformConfiguration = this
}
