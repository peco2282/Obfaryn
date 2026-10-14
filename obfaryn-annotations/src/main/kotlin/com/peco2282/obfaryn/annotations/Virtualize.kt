package com.peco2282.obfaryn.annotations

/**
 * Explicitly selects the annotated class or method for Obfaryn virtualization.
 *
 * Method annotations override a class-level [HotPath]. Eligibility restrictions still apply.
 */
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.PROPERTY_SETTER,
)
@Retention(AnnotationRetention.BINARY)
annotation class Virtualize
