package com.peco2282.obfaryn.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

class ObfarynAssemblyPluginTest {
  @TempDir lateinit var dir: Path

  private fun write(path: Path, content: String) {
    Files.createDirectories(path.parent)
    Files.writeString(path, content.trimIndent())
  }

  private fun classNewName(mapping: String, original: String) =
    mapping.lineSequence().first { it.startsWith("$original -> ") }.substringAfterLast("-> ").trim()

  private fun methodNewName(mapping: String, prefix: String) =
    mapping.lineSequence().first { it.trim().startsWith(prefix) }.substringAfterLast("-> ").trim()

  @Test fun crossArtifactRenameStaysConsistentInKotlinAndGroovyBuilds() {
    for (kotlin in listOf(true, false)) {
      val project = dir.resolve(if (kotlin) "kotlin" else "groovy")
      Files.createDirectories(project)
      write(project.resolve("settings.gradle"), """
        rootProject.name = 'sample'
        include('api', 'app')
      """)

      // Internal API: Greeter.greet() -> "hi", plus a plaintext literal kept (encryption off here).
      write(project.resolve("api/src/main/java/api/Greeter.java"), """
        package api;
        public class Greeter {
          public String greet() { return "hi"; }
          public String token() { return "APIKEY-PLAINTEXT-VALUE"; }
        }
      """)
      // Consumer: references api.Greeter across the artifact boundary, plus a literal app encrypts.
      write(project.resolve("app/src/main/java/app/Main.java"), """
        package app;
        public class Main {
          public static String run() { return new api.Greeter().greet(); }
          public static String secret() { return "SECRET-TOKEN-VALUE-XYZ"; }
        }
      """)

      if (kotlin) {
        write(project.resolve("api/build.gradle.kts"), """
          plugins { java; id("com.peco2282.obfaryn") }
          obfaryn { applicationPackages = listOf("api"); stringEncryption = false; numberObfuscation = false; verifyOutput = true }
        """)
        write(project.resolve("app/build.gradle.kts"), """
          plugins { java; id("com.peco2282.obfaryn") }
          dependencies { implementation(project(":api")) }
          obfaryn { applicationPackages = listOf("app"); stringEncryption = true; numberObfuscation = false; verifyOutput = true }
        """)
        write(project.resolve("build.gradle.kts"), """
          plugins { id("com.peco2282.obfaryn") }
          obfarynAssembly {
            artifact(project(":api"))
            artifact(project(":app"))
            rename { classes = true; methods = true; fields = true }
            seed = 7L
            outputDirectory = "build/obf"
          }
        """)
      } else {
        write(project.resolve("api/build.gradle"), """
          plugins { id 'java'; id 'com.peco2282.obfaryn' }
          obfaryn { applicationPackages = ['api']; stringEncryption = false; numberObfuscation = false; verifyOutput = true }
        """)
        write(project.resolve("app/build.gradle"), """
          plugins { id 'java'; id 'com.peco2282.obfaryn' }
          dependencies { implementation project(':api') }
          obfaryn { applicationPackages = ['app']; stringEncryption = true; numberObfuscation = false; verifyOutput = true }
        """)
        write(project.resolve("build.gradle"), """
          plugins { id 'com.peco2282.obfaryn' }
          obfarynAssembly {
            artifact(project(':api'))
            artifact(project(':app'))
            rename { classes = true; methods = true; fields = true }
            seed = 7
            outputDirectory = 'build/obf'
          }
        """)
      }

      GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
        .withArguments("obfuscateAssembly", "--stacktrace").build()

      val apiOut = project.resolve("build/obf/api-obfuscated.jar")
      val appOut = project.resolve("build/obf/app-obfuscated.jar")
      assertTrue(Files.isRegularFile(apiOut) && Files.isRegularFile(appOut)) { "both artifacts are written" }
      val mapping = Files.readString(project.resolve("build/obfaryn/assembly.mapping.txt"))

      // The API class was renamed and the single mapping covers both artifacts.
      assertNotEquals("api/Greeter", classNewName(mapping, "api/Greeter"))
      assertTrue(mapping.contains("app/Main -> "))

      // Load both renamed JARs together (originals are not on this classpath) and run the consumer.
      URLClassLoader(arrayOf(apiOut.toUri().toURL(), appOut.toUri().toURL()), null).use { loader ->
        val mainNew = classNewName(mapping, "app/Main").replace('/', '.')
        val runNew = methodNewName(mapping, "app/Main.run()")
        assertEquals("hi", loader.loadClass(mainNew).getMethod(runNew).invoke(null)) {
          "the consumer's cross-artifact reference followed the global rename"
        }
      }

      // Per-artifact body settings still apply inside the assembly.
      assertTrue(decompressedClasses(apiOut).contains("APIKEY-PLAINTEXT-VALUE")) { "api kept its literal plaintext" }
      assertFalse(decompressedClasses(appOut).contains("SECRET-TOKEN-VALUE-XYZ")) { "app encrypted its literal" }
    }
  }

  private fun decompressedClasses(jarPath: Path): String = java.util.jar.JarFile(jarPath.toFile()).use { jar ->
    buildString {
      val entries = jar.entries()
      while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        if (entry.name.endsWith(".class")) append(String(jar.getInputStream(entry).readBytes(), Charsets.ISO_8859_1))
      }
    }
  }
}
