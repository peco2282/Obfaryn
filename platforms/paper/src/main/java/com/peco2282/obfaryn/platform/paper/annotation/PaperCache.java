package com.peco2282.obfaryn.platform.paper.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.Supplier;

/**
 * Marks a public concrete {@link Supplier} with a public no-argument
 * constructor as a cache factory. Factory contracts are validated at build time.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface PaperCache {
}
