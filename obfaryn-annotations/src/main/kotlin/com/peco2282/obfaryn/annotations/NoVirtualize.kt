package com.peco2282.obfaryn.annotations

/**
 * Excludes the annotated class or method from Obfaryn virtualization while leaving every other
 * protection in place.
 *
 * Unlike [HotPath], which suppresses all runtime body obfuscation, this only opts out of the
 * interpreter: string encryption, numeric obfuscation, control-flow protection and the remaining
 * body passes still apply to the annotated code.
 *
 * On a method it removes that method from virtualization even when the enclosing class carries
 * [Virtualize] or an explicit selector would match it. On a class it prevents every method from
 * being virtualized, except a method that carries its own [Virtualize]. A method annotation
 * therefore overrides the class decision in both directions. Combining this with [Virtualize] on
 * the same declaration is a conflict and fails validation.
 */
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.PROPERTY_SETTER,
)
@Retention(AnnotationRetention.BINARY)
annotation class NoVirtualize
