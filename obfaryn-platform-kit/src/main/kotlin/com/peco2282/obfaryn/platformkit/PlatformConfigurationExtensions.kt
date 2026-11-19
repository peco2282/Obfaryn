/*
 * Platform-kit · keep/exclude actions, and the typical provider flow.
 *
 * These wrap `PlatformConfiguration.keep*` / `excludeMethodFromVirtualization` so a provider can pass
 * a real `Class`/`Method`/`Field`/`Constructor` instead of hand-written names (classpath types only).
 *
 * A provider's `configure` normally: (1) detects a framework's entrypoints with the detection helpers
 * in this kit, (2) keeps their names so the framework can still find them, then (3) protects them and
 * everything they call from body transformation such as virtualization. Putting the pieces together:
 *
 * ```kotlin
 * // inside a PlatformExtension's configure(context, config, options):
 * val handlers: Set<MemberKey> = context.classes.values.asSequence()
 *   .filter { context.isSubtypeOf(it.name, "org/bukkit/event/Listener") } // Subtyping.kt
 *   .flatMap { owner ->
 *     owner.methods.asSequence()
 *       .filter { it.isAnnotatedWith("org.bukkit.event.EventHandler") }     // Annotations.kt
 *       .map { MemberKey.of(owner.name, it.name, it.desc) }                 // Descriptors.kt (MemberKey.of)
 *   }.toSet()
 *
 * handlers.forEach { config.keepMethod(it.owner, it.name, it.desc) }        // keep the callbacks
 * context.methodCallClosure(handlers).forEach {                            // CallGraph.kt: callbacks + callees
 *   config.excludeMethodFromVirtualization(it.owner, it.name, it.desc)      // keep hot behavior interpreted-free
 * }
 * ```
 *
 * The API's `PlatformExtension` / `PlatformConfiguration` define the actual `configure` hooks.
 */
package com.peco2282.obfaryn.platformkit

import com.peco2282.obfaryn.api.PlatformConfiguration
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * [PlatformConfiguration.keepClass] for the real class instead of a hand-written name. Only
 * usable when [type] is actually on the current classpath.
 */
fun PlatformConfiguration.keepClass(type: Class<*>): PlatformConfiguration = keepClass(type.internalName)

/** @see PlatformConfiguration.keepClass */
fun PlatformConfiguration.keepClassNameOnly(type: Class<*>): PlatformConfiguration = keepClassNameOnly(type.internalName)

/**
 * [PlatformConfiguration.keepMethod] for the real method instead of hand-writing its owner, name
 * and descriptor. Only usable when the declaring class is actually on the current classpath.
 */
fun PlatformConfiguration.keepMethod(method: Method): PlatformConfiguration =
  keepMethod(method.declaringClass.internalName, method.name, method.descriptor)

/** @see PlatformConfiguration.keepMethod */
fun PlatformConfiguration.keepConstructor(constructor: Constructor<*>): PlatformConfiguration =
  keepMethod(constructor.declaringClass.internalName, "<init>", constructor.descriptor)

/** [PlatformConfiguration.keepField] for the real field instead of hand-writing its owner and name. */
fun PlatformConfiguration.keepField(field: Field): PlatformConfiguration =
  keepField(field.declaringClass.internalName, field.name)

/**
 * [PlatformConfiguration.excludeMethodFromVirtualization] for the real method instead of
 * hand-writing its owner, name and descriptor.
 */
fun PlatformConfiguration.excludeMethodFromVirtualization(
  method: Method,
  reason: String = "platform performance policy",
): PlatformConfiguration =
  excludeMethodFromVirtualization(method.declaringClass.internalName, method.name, method.descriptor, reason)
