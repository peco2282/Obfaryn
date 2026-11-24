package com.peco2282.obfaryn.core.annotation

/**
 * Back-compatible alias for the annotation reader promoted to the public API.
 *
 * The implementation now lives in [com.peco2282.obfaryn.api.annotation.ObfuscationAnnotationPolicy]
 * so host-loaded extension and external recipes can resolve annotation-driven selection without a
 * core dependency. Core code may continue to reference this name; new code should prefer the API type.
 */
typealias ObfuscationAnnotationPolicy = com.peco2282.obfaryn.api.annotation.ObfuscationAnnotationPolicy
