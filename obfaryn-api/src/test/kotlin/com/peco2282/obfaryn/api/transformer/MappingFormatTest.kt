package com.peco2282.obfaryn.api.transformer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MappingFormatTest {
  @Test fun emptyTablesRenderToEmptyString() {
    assertEquals("", MappingFormat.render(emptyMap(), emptyMap(), emptyMap()))
    assertTrue(MappingFormat.parse("").isEmpty)
  }

  @Test fun forwardTablesRoundTripIncludingPackages() {
    val model = NameMapping(
      classes = linkedMapOf("com/example/Service" to "a/b", "com/example/Other" to "a/c"),
      methods = linkedMapOf(
        MemberKey("com/example/Service", "work", "(Ljava/lang/String;)V") to "a",
        // Overloads share a name but differ by descriptor; both must survive the round trip.
        MemberKey("com/example/Service", "work", "(I)V") to "b",
      ),
      fields = linkedMapOf(MemberKey("com/example/Service", "value", "I") to "c"),
      packages = linkedMapOf("com/example" to "a"),
    )
    val parsed = MappingFormat.parse(MappingFormat.render(model))
    assertEquals(model.classes, parsed.classes)
    assertEquals(model.methods, parsed.methods)
    assertEquals(model.fields, parsed.fields)
    assertEquals(model.packages, parsed.packages)
  }

  @Test fun packageLinesAreDistinctFromClassLines() {
    val text = MappingFormat.render(
      classes = linkedMapOf("com/example/Service" to "a/b"),
      methods = emptyMap(),
      fields = emptyMap(),
      packages = linkedMapOf("com/example" to "a"),
    )
    assertTrue(text.lineSequence().any { it == "com/example/Service -> a/b" })
    assertTrue(text.lineSequence().any { it == "package com/example -> a" })
    // A class whose name is not a package line must not be read as one.
    val parsed = MappingFormat.parse(text)
    assertEquals(mapOf("com/example/Service" to "a/b"), parsed.classes)
    assertEquals(mapOf("com/example" to "a"), parsed.packages)
  }

  @Test fun provenanceSourcesRoundTripAndAccumulate() {
    val model = NameMapping(
      classes = linkedMapOf("api/Greeter" to "a/a"),
      sources = listOf(MappingSource("api.jar", "sha256:abc"), MappingSource("impl.jar", "sha256:def")),
    )
    val parsed = MappingFormat.parse(MappingFormat.render(model))
    assertEquals(model.sources, parsed.sources)
    assertEquals(model.classes, parsed.classes)
  }

  @Test fun unknownCommentLinesAreIgnored() {
    val parsed = MappingFormat.parse(
      """
      # obfaryn mapping format ${MappingFormat.FORMAT_VERSION}
      # source api.jar sha256:abc

      com/example/Service -> a/b
        com/example/Service.work(Ljava/lang/String;)V -> a
      """.trimIndent()
    )
    assertEquals(mapOf("com/example/Service" to "a/b"), parsed.classes)
    assertEquals("a", parsed.methods[MemberKey("com/example/Service", "work", "(Ljava/lang/String;)V")])
    assertEquals(listOf(MappingSource("api.jar", "sha256:abc")), parsed.sources)
  }

  @Test fun malformedLineIsRejected() {
    assertThrows(IllegalArgumentException::class.java) { MappingFormat.parse("com/example/Service a/b") }
  }
}
