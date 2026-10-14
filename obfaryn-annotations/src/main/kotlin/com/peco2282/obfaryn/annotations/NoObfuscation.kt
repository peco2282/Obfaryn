package com.peco2282.obfaryn.annotations

/**
 * Keeps the annotated declaration's name and body free from Obfaryn transformations.
 * References still follow renames applied to other declarations so the output remains valid.
 */
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.FIELD,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.PROPERTY_SETTER,
)
@Retention(AnnotationRetention.BINARY)
annotation class NoObfuscation
