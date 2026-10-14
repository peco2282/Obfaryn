package com.peco2282.obfaryn.core.rename

interface NameGenerator { fun next(): String }

class SequentialNameGenerator : NameGenerator {
  private var index = 0
  private val reserved = setOf("do", "if", "in", "int", "new", "try", "for", "var", "val", "fun", "is", "as")
  override fun next(): String {
    while (true) {
      var n = index++
      val result = StringBuilder()
      do {
        result.append(('a'.code + n % 26).toChar())
        n = n / 26 - 1
      } while (n >= 0)
      val candidate = result.reverse().toString()
      if (candidate !in reserved) return candidate
    }
  }
}
