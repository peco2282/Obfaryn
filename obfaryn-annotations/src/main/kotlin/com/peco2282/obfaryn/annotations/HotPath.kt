package com.peco2282.obfaryn.annotations

/**
 * Prevents the annotated class or method from being virtualized.
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
