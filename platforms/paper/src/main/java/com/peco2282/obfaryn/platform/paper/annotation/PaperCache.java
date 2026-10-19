package com.peco2282.obfaryn.platform.paper.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.Supplier;

/**
 * Marks a public concrete {@link Supplier} with a public no-argument
 * constructor as a cache factory during obfuscation. The marker is consumed and
 * removed by the Paper platform; it is not used at runtime. CLASS retention keeps
 * it available in the input bytecode for build-time validation.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface PaperCache {
}
