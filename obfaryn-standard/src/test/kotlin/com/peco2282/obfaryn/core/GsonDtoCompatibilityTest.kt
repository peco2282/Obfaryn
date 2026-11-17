package com.peco2282.obfaryn.core

import com.google.gson.Gson
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

class GsonDtoCompatibilityTest {
  @TempDir lateinit var dir: Path

  @Test fun serializedNamesRemainStableWhileDtoFieldsAreRenamed() {
    val gsonJar = Paths.get(Gson::class.java.protectionDomain.codeSource.location.toURI())
    val input = JavaJarFixture.compile(
      dir,
      mapOf(
        "dto/Payload" to """
          package dto;
          import com.google.gson.annotations.SerializedName;

          public final class Payload {
            @SerializedName("user_id") public int id;
            @SerializedName("display_name") public String name;
            @SerializedName("enabled") public boolean active;

            public Payload(int id, String name, boolean active) {
              this.id = id;
              this.name = name;
              this.active = active;
            }
          }
        """.trimIndent(),
        "dto/Workload" to """
          package dto;
          import com.google.gson.Gson;

          public final class Workload {
            public static String roundTrip(int id) {
              Gson gson = new Gson();
              Payload original = new Payload(id, "item-" + id, id % 2 == 0);
              String json = gson.toJson(original);
              Payload decoded = gson.fromJson(json, Payload.class);
              boolean equal = decoded.id == original.id && decoded.name.equals(original.name)
                  && decoded.active == original.active;
              return equal + ":" + json;
            }
          }
        """.trimIndent(),
      ),
      additionalClasspath = listOf(gsonJar),
    )

    for (seed in listOf(0L, 1L, 20261117L, Long.MIN_VALUE)) {
      val output = dir.resolve("gson-$seed.jar")
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
          classpath = listOf(gsonJar),
        )
      ).obfuscate(input, output)
      assertTrue(result.classesRenamed > 0, "seed=$seed: DTO classes must actually be renamed")
      assertTrue(result.fieldsRenamed > 0, "seed=$seed: DTO fields must actually be renamed")

      val mappedWorkload = Files.readAllLines(requireNotNull(result.mapping))
        .first { it.startsWith("dto/Workload -> ") }
        .substringAfter(" -> ")
        .replace('/', '.')
      URLClassLoader(arrayOf(output.toUri().toURL(), gsonJar.toUri().toURL()), null).use { loader ->
        val method = loader.loadClass(mappedWorkload).getMethod("roundTrip", Int::class.javaPrimitiveType)
        for (id in intArrayOf(0, 1, -5, 42, 1000)) {
          val active = id % 2 == 0
          assertEquals(
            "true:{\"user_id\":$id,\"display_name\":\"item-$id\",\"enabled\":$active}",
            method.invoke(null, id),
            "seed=$seed id=$id: Gson's external JSON schema must not follow obfuscated JVM field names",
          )
        }
      }
    }
  }
}
