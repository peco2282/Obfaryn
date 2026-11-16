package com.peco2282.obfaryn.core.jar

/** Returns every parent directory needed to expose [entryNames] through class-loader resources. */
fun jarDirectoryNames(entryNames: Iterable<String>): List<String> = buildSet {
  for (name in entryNames) {
    var separator = name.indexOf('/')
    while (separator >= 0) {
      add(name.substring(0, separator + 1))
      separator = name.indexOf('/', separator + 1)
    }
  }
}.sorted()
