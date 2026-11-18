package com.peco2282.obfaryn.core

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.peco2282.obfaryn.api.protection.ControlFlowMode
import com.peco2282.obfaryn.core.fixtures.JavaJarFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Jackson is a second reflection-based serializer, keyed by `@JsonProperty` rather than by JVM
 * field name (see [GsonDtoCompatibilityTest] for the Gson equivalent). The external JSON schema
 * must stay stable while the underlying JVM fields and classes are renamed, because Jackson maps
 * a JSON key to a member through annotation introspection, not through the member's bytecode name.
 * `@JsonPropertyOrder` pins the property order so the full JSON text is deterministic to assert.
 */
class JacksonDtoCompatibilityTest {
  @TempDir lateinit var dir: Path

  @Test fun jsonPropertyNamesRemainStableWhileDtoFieldsAreRenamed() {
    val jacksonJars = listOf(
      Paths.get(ObjectMapper::class.java.protectionDomain.codeSource.location.toURI()),
      Paths.get(JsonProcessingException::class.java.protectionDomain.codeSource.location.toURI()),
      Paths.get(JsonProperty::class.java.protectionDomain.codeSource.location.toURI()),
    )
    val input = JavaJarFixture.compile(
      dir,
      mapOf(
        "dto/Payload" to """
          package dto;
          import com.fasterxml.jackson.annotation.JsonProperty;
          import com.fasterxml.jackson.annotation.JsonPropertyOrder;

          @JsonPropertyOrder({"user_id", "display_name", "enabled"})
          public final class Payload {
            @JsonProperty("user_id") public int id;
            @JsonProperty("display_name") public String name;
            @JsonProperty("enabled") public boolean active;

            public Payload() {}

            public Payload(int id, String name, boolean active) {
              this.id = id;
              this.name = name;
              this.active = active;
            }
          }
        """.trimIndent(),
        "dto/Workload" to """
          package dto;
          import com.fasterxml.jackson.databind.ObjectMapper;

          public final class Workload {
            public static String roundTrip(int id) throws Exception {
              ObjectMapper mapper = new ObjectMapper();
              Payload original = new Payload(id, "item-" + id, id % 2 == 0);
              String json = mapper.writeValueAsString(original);
              Payload decoded = mapper.readValue(json, Payload.class);
              boolean equal = decoded.id == original.id && decoded.name.equals(original.name)
                  && decoded.active == original.active;
              return equal + ":" + json;
            }
          }
        """.trimIndent(),
      ),
      additionalClasspath = jacksonJars,
    )

    for (seed in listOf(0L, 1L, 20261118L, Long.MIN_VALUE)) {
      val output = dir.resolve("jackson-$seed.jar")
      val result = Obfuscator(
        ObfuscationConfig(
          applicationPackages = listOf("dto"),
          seed = seed,
          protectionLevel = ProtectionLevel.STRONG,
          controlFlowMode = ControlFlowMode.LIGHT,
          renameClasses = true,
          renameMethods = true,
          renameFields = true,
          flattenPackages = true,
          targetPackage = "hidden",
          keep = listOf("dto.Workload#roundTrip(I)Ljava/lang/String;"),
          verifyOutput = true,
          excludeVirtualization = listOf(".*"),
          classpath = jacksonJars,
        )
      ).obfuscate(input, output)
      assertTrue(result.classesRenamed > 0, "seed=$seed: DTO classes must actually be renamed")
      assertTrue(result.fieldsRenamed > 0, "seed=$seed: DTO fields must actually be renamed")

      val mappedWorkload = Files.readAllLines(requireNotNull(result.mapping))
        .first { it.startsWith("dto/Workload -> ") }
        .substringAfter(" -> ")
        .replace('/', '.')
      val classpath = (listOf(output) + jacksonJars).map { it.toUri().toURL() }.toTypedArray()
      URLClassLoader(classpath, null).use { loader ->
        val method = loader.loadClass(mappedWorkload).getMethod("roundTrip", Int::class.javaPrimitiveType)
        for (id in intArrayOf(0, 1, -5, 42, 1000)) {
          val active = id % 2 == 0
          assertEquals(
            "true:{\"user_id\":$id,\"display_name\":\"item-$id\",\"enabled\":$active}",
            method.invoke(null, id),
            "seed=$seed id=$id: Jackson's external JSON schema must not follow obfuscated JVM field names",
          )
        }
      }
    }
  }
}
