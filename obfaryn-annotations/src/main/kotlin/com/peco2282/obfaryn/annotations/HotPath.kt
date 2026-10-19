package com.peco2282.obfaryn.annotations

/**
 * Prevents runtime body obfuscation (strings, numbers, control flow, and virtualization).
 *
 * Use this for code whose call frequency makes the interpreter overhead undesirable, such as
 * tick callbacks, entity scans, packet handlers, and other latency-sensitive paths.
 */
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.PROPERTY_SETTER,
)
@Retention(AnnotationRetention.BINARY)
annotation class HotPath
