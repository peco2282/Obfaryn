package com.peco2282.obfaryn.core

import java.util.Collections
import java.util.stream.Collectors
import java.util.stream.Stream

fun <T> Stream<T>.unmodifiableList(): List<T> = Collections.unmodifiableList(this.collect(Collectors.toList()))

fun platformClassLoader(): ClassLoader = ClassLoader.getSystemClassLoader().parent

fun <T: Throwable> assertInstanceOf(clazz: Class<T>, throwable: Throwable?) {
  if (throwable == null) throw AssertionError("Expected ${clazz.name} but got null")
  if (!clazz.isInstance(throwable)) throw AssertionError("Expected ${clazz.name} but got ${throwable::class.java.name}")
}
