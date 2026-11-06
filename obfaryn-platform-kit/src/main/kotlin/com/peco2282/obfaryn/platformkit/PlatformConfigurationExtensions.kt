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
