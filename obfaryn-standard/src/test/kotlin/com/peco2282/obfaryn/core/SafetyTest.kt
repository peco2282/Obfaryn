package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.keep.KeepRules
import com.peco2282.obfaryn.core.mapping.Mapping
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SafetyTest {
  @Test fun recursiveKeepGlobAndMappingRoundTrip() {
    val keep = KeepRules()
    keep.add("com.example.api.**")
    assertTrue(keep.className("com/example/api/Service"))
    assertTrue(keep.className("com/example/api/internal/Service"))
    assertFalse(keep.className("com/example/other/Service"))

    val mapping = Mapping.parse("""
      com/example/Service -> a/b
        com/example/Service.work(Ljava/lang/String;)V -> a
        com/example/Service.value:I -> b
    """.trimIndent())
    assertEquals("com/example/Service", mapping.originalClass("a/b"))
    assertEquals("work", mapping.originalMethod("a.b", "a"))
    assertEquals("Exception in thread \"main\"\n\tat com.example.Service.work(SourceFile:12)\n",
      mapping.deobfuscateStacktrace("Exception in thread \"main\"\n\tat a.b.a(SourceFile:12)\n"))
  }
}
