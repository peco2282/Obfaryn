package com.peco2282.obfaryn.api.transformer

/** Run-specific scope, preservation rules and analysis supplied by the execution host. */
interface TransformationInputs {
  fun isApplicationClass(internalName: String): Boolean = false
  fun keepConstant(owner: String, field: String): Boolean = false
  fun shouldStripKotlinMetadata(internalName: String): Boolean = false

  companion object {
    /** Unknown application scope must not broaden constant conversion to library classes. */
    val EMPTY: TransformationInputs = object : TransformationInputs {}
  }
}