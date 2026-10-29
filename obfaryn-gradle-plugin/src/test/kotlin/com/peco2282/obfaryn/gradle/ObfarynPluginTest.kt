package com.peco2282.obfaryn.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

class ObfarynPluginTest {
  @TempDir lateinit var dir: Path

  @Test fun apiOnlyPackageProvidersWorkInKotlinAndGroovyBuilds() {
    for (kotlin in listOf(true, false)) {
      val project = dir.resolve(if (kotlin) "kotlin" else "groovy")
      Files.createDirectories(project)
      Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'sample'")
      val jars = System.getProperty("obfaryn.test.extensionClasspath").split(java.io.File.pathSeparator)
      jars.forEachIndexed { index, path -> Files.copy(Path.of(path), project.resolve("extension-$index.jar")) }
      val files = jars.indices.joinToString(", ") { "\"extension-$it.jar\"" }
      val configuration = if (kotlin) """
        plugins { java; id("com.peco2282.obfaryn") }
        dependencies { obfarynPlatform(files($files)) }
        obfaryn {
          useDefaultPackage = false
          packageIds = listOf("example-padding")
          reports { methods = "build/methods.json" }
          rules { method("example/.*") { feature("example:padding", false) } }
          pipeline { check(this["example-padding"] != null); check(this["rename"] == null) }
        }
      """ else """
        plugins { id 'java'; id 'com.peco2282.obfaryn' }
        dependencies { obfarynPlatform files($files) }
        obfaryn {
          useDefaultPackage = false
          packageIds = ['example-padding']
          reports { methods = 'build/methods.json' }
          rules { method('example/.*') { feature('example:padding', false) } }
          pipeline { assert it['example-padding'] != null; assert it['rename'] == null }
        }
      """
      Files.writeString(project.resolve(if (kotlin) "build.gradle.kts" else "build.gradle"), configuration.trimIndent())
      val source = project.resolve("src/main/java/example/Main.java")
      Files.createDirectories(source.parent)
      Files.writeString(source, "package example; public class Main { public static int answer() { return 7; } }")
      val build = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
        .withArguments("obfuscateJar", "--stacktrace").build()
      assertTrue(build.output.contains("Obfaryn example-padding"), build.output)
      assertFalse(build.output.contains("Obfaryn string-encryption"), build.output)
      assertTrue(Files.readString(project.resolve("build/methods.json")).contains("feature-policy:example:padding"))
      java.net.URLClassLoader(arrayOf(project.resolve("build/libs/sample-obfuscated.jar").toUri().toURL()), null).use {
        assertEquals(7, it.loadClass("example.Main").getMethod("answer").invoke(null))
      }
    }
  }

  @Test fun loadsPaperFromDedicatedClasspath() {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"")
    val jars = System.getProperty("obfaryn.test.paperClasspath").split(java.io.File.pathSeparator)
    jars.forEachIndexed { index, path -> Files.copy(Path.of(path), dir.resolve("platform-$index.jar")) }
    val files = jars.indices.joinToString(", ") { "\"platform-$it.jar\"" }
    val script = dir.resolve("build.gradle.kts")
    Files.writeString(script, """
      plugins { java; id("com.peco2282.obfaryn") }
      dependencies { obfarynPlatform(files($files)) }
      obfaryn { platform = "paper" }
      tasks.register("checkPlatformIsolation") {
        doLast {
          check(configurations.compileClasspath.get().files.isEmpty())
          check(configurations.runtimeClasspath.get().files.isEmpty())
        }
      }
    """.trimIndent())
    val source = dir.resolve("src/main/java/example/App.java")
    Files.createDirectories(source.parent)
    Files.writeString(source, "package example; public class App { public String hello() { return \"secret\"; } }")
    val descriptor = dir.resolve("src/main/resources/plugin.yml")
    Files.createDirectories(descriptor.parent)
    Files.writeString(descriptor, "name: Sample\nversion: 1\nmain: example.App\n")
    val runner = GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath()
    runner.withArguments("obfuscateJar", "checkPlatformIsolation", "--stacktrace").build()
    JarFile(dir.resolve("build/libs/sample-obfuscated.jar").toFile()).use { jar ->
      assertNotNull(jar.getJarEntry("example/App.class"))
      assertNotNull(jar.getJarEntry("plugin.yml"))
      assertFalse(jar.entries().asSequence().any { it.name.startsWith("com/peco2282/obfaryn/platform/") })
    }
    // A subsequent build without the dependency must not retain the provider.
    Files.writeString(script, Files.readString(script).replace("obfarynPlatform(files($files))", ""))
    assertTrue(runner.withArguments("obfuscateJar", "--stacktrace").buildAndFail().output.contains("Unknown platform: paper"))
  }

  @Test fun nestedOptionsShareLegacyValues() {
    val extension = ProjectBuilder.builder().build()
      .extensions.create("obfaryn", ObfarynExtension::class.java)
    extension.string { it.enabled = false; it.includeConstantFields = false }
    extension.number { it.enabled = false }
    extension.metadata {
      it.stripDebugInfo = false
      it.stripKotlinMetadata = true
      it.kotlinMetadataMode = "preserve"
      it.stripGenericSignatures = true
      it.stripMethodParameters = false
    }
    assertFalse(extension.stringEncryption)
    assertFalse(extension.numberObfuscation)
    assertFalse(extension.stripDebugInfo)
    assertTrue(extension.stripKotlinMetadata)
    assertEquals("preserve", extension.kotlinMetadataMode)
    assertTrue(extension.stripGenericSignatures)
    assertFalse(extension.stripMethodParameters)
    extension.stringEncryption = true
    extension.stripDebugInfo = true
    assertTrue(extension.string.enabled)
    assertTrue(extension.metadata.stripDebugInfo)
    extension.calls { it.enabled = true; it.prelink = true }
    extension.arithmetic { it.enabled = true; it.maxOperationsPerMethod = 4 }
    extension.reports { it.methods = "methods.json"; it.virtualization = "vm.json" }
    extension.controlFlow { it.mode = "NONE" }
    assertTrue(extension.callIndirection)
    assertTrue(extension.prelinkCalls)
    assertTrue(extension.integerArithmetic)
    assertEquals(4, extension.maxArithmeticOperationsPerMethod)
    assertEquals("methods.json", extension.transformationReport)
    assertEquals("vm.json", extension.virtualization.report)
    assertEquals("none", extension.controlFlowMode)
    extension.prelinkCalls = false
    extension.integerArithmetic = false
    extension.transformationReport = "other.json"
    assertFalse(extension.calls.prelink)
    assertFalse(extension.arithmetic.enabled)
    assertEquals("other.json", extension.reports.methods)
    assertThrows(IllegalArgumentException::class.java) { extension.controlFlow { it.mode = "invalid" } }
    extension.rules { rules ->
      rules.method("example/.*") { rule ->
        rule.calls = false
        rule.controlFlow = "light"
        rule.performance { it.maxAddedInstructions = 64; it.growthAllowanceInstructions = 48 }
      }
    }
    val rule = extension.protectionRules.single()
    assertEquals(mapOf(com.peco2282.obfaryn.api.protection.BodyFeature.CALLS to false), rule.features)
    assertEquals(com.peco2282.obfaryn.api.protection.ControlFlowMode.LIGHT, rule.controlFlow)
    assertEquals(64, rule.budget!!.maxAddedInstructions)
    assertEquals(48, rule.budget!!.growthAllowanceInstructions)
    assertNull(rule.budget!!.maxInputInstructions)
    assertThrows(IllegalArgumentException::class.java) {
      extension.rules { it.method("[") { rule -> rule.calls = false } }
    }
    assertEquals(1, extension.protectionRules.size)
  }

  @Test fun nestedDslConfiguresTransformationsAndOutputPaths() {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"")
    Files.writeString(dir.resolve("build.gradle.kts"), """
      import com.peco2282.obfaryn.api.transformer.StringEncryptionResult
      import com.peco2282.obfaryn.api.transformer.StringEncryptionCodec
      import com.peco2282.obfaryn.transformer.XorStringEncryptionCodec
      import java.util.Random
      import com.peco2282.obfaryn.api.ObfuscationContext
      import com.peco2282.obfaryn.api.transformer.Transformer
      import com.peco2282.obfaryn.core.Obfuscator
      plugins { java; id("com.peco2282.obfaryn") }
      check(Obfuscator::class.java.name == "com.peco2282.obfaryn.core.Obfuscator")
      tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }
      obfaryn {
        platform = "generic"
        seed = 4L
        applicationPackages = listOf("example")
        protection = "strong"
        controlFlow { mode = "none" }
        calls { enabled = true; prelink = true }
        arithmetic { enabled = true; maxOperationsPerMethod = 4 }
        reports { methods = "build/method-decisions.json" }
        rules {
          method("example/.*") {
            calls = false
            controlFlow = "light"
            performance { maxAddedInstructions = 256; maxGrowthPercent = 300; growthAllowanceInstructions = 48 }
          }
        }
        performance { maxAddedInstructions = 256; maxGrowthPercent = 300; growthAllowanceInstructions = 48 }
        pipeline {
          add(object : Transformer {
            override val id = "custom-marker"
            override val before = setOf("string-encryption")
            override val requiresFrameRecomputation = true
            override fun transform(context: ObfuscationContext) {
              project.file("pipeline-used").writeText("yes")
            }
          })
        }
        string {
          enabled = true
          includeConstantFields = false
          random = Random(12)
          strategy = object : StringEncryptionCodec by XorStringEncryptionCodec() {
            override fun encrypt(value: String, key: Int): StringEncryptionResult {
              project.file("strategy-used").writeText("yes")
              return XorStringEncryptionCodec().encrypt(value, key)
            }
          }
        }
        number { enabled = false; includeConstantFields = false }
        metadata {
          stripDebugInfo = false
          kotlinMetadataMode = "preserve"
          stripGenericSignatures = true
          stripMethodParameters = true
        }
        rename { classes = false; methods = false; fields = false }
        output {
          directory = "build/protected jars"
          fileName = "release.jar"
          mapping = "build/maps/release.txt"
        }
      }
    """.trimIndent())
    val source = dir.resolve("src/main/java/example/App.java")
    Files.createDirectories(source.parent)
    Files.writeString(source, """
      package example;
      public class App<T> {
        public static final String TEXT = "field secret";
        public static String message(String input) { return "method secret"; }
      }
    """.trimIndent())
    val runner = GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath()
      .withArguments("help", "--stacktrace")
    runner.build()
    assertFalse(Files.exists(dir.resolve("build/protected jars/release.jar")))
    assertFalse(Files.exists(dir.resolve("build/maps/release.txt")))
    assertFalse(Files.exists(dir.resolve("strategy-used")))
    assertFalse(Files.exists(dir.resolve("pipeline-used")))
    runner.withArguments("obfuscateJar", "--stacktrace")
    val transformed = runner.build()
    assertFalse(transformed.output.contains("Obfaryn control-flow:"), transformed.output)
    val output = dir.resolve("build/protected jars/release.jar")
    assertTrue(Files.exists(output))
    assertTrue(Files.exists(dir.resolve("build/maps/release.txt")))
    assertTrue(Files.exists(dir.resolve("strategy-used")))
    assertTrue(Files.exists(dir.resolve("pipeline-used")))
    assertTrue(Files.exists(dir.resolve("build/method-decisions.json")))
    assertTrue(Files.readString(dir.resolve("build/method-decisions.json")).contains("feature-policy:callIndirection"))
    assertFalse(Files.exists(dir.resolve("build/libs/sample-obfuscated.jar")))
    JarFile(output.toFile()).use { jar ->
      val bytes = jar.getInputStream(jar.getJarEntry("example/App.class")).readBytes()
      val text = String(bytes, Charsets.ISO_8859_1)
      assertTrue(text.contains("field secret"))
      assertFalse(text.contains("method secret"))
      assertTrue(text.contains("SourceFile"))
    }
    java.net.URLClassLoader(arrayOf(output.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
      val type = loader.loadClass("example.App")
      assertEquals(0, type.typeParameters.size)
      val method = type.getMethod("message", String::class.java)
      assertFalse(method.parameters.single().isNamePresent)
      assertEquals("method secret", method.invoke(null, "ignored"))
    }
    val script = dir.resolve("build.gradle.kts")
    Files.writeString(script, Files.readString(script).replace(
      "directory = \"build/protected jars\"", "file = \"build/explicit/output.jar\""))
    runner.build()
    assertTrue(Files.exists(dir.resolve("build/explicit/output.jar")))
  }

  @Test fun groovyDslAcceptsNestedBlocks() {
    Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'sample'")
    Files.writeString(dir.resolve("build.gradle"), """
      plugins { id 'java'; id 'com.peco2282.obfaryn' }
      obfaryn {
        platform = 'generic'
        applicationPackages = ['example']
        protection = 'strong'
        controlFlow { mode = 'none' }
        calls { enabled = true; prelink = true }
        arithmetic { enabled = true; maxOperationsPerMethod = 4 }
        reports { methods = 'build/methods.json' }
        rules {
          method('example/.*') {
            calls = false
            controlFlow = 'none'
            performance { maxAddedInstructions = 64; growthAllowanceInstructions = 48 }
          }
        }
        string { enabled = false; includeConstantFields = false }
        number { enabled = false }
        metadata { stripDebugInfo = false }
        output { directory = 'build/secure'; fileName = 'app.jar' }
      }
    """.trimIndent())
    GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath()
      .withArguments("obfuscateJar", "--stacktrace").build()
    assertTrue(Files.exists(dir.resolve("build/secure/app.jar")))
    assertTrue(Files.exists(dir.resolve("build/secure/app.mapping.txt")))
    assertTrue(Files.exists(dir.resolve("build/methods.json")))
  }

  @Test fun outputCannotOverwriteTheInputOrMapping() {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"")
    val script = dir.resolve("build.gradle.kts")
    Files.writeString(script, """
      plugins { java; id("com.peco2282.obfaryn") }
      obfaryn { output { file = "build/libs/sample.jar" } }
    """.trimIndent())
    val runner = GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath()
      .withArguments("obfuscateJar", "--stacktrace")
    assertTrue(runner.buildAndFail().output.contains("must use different paths"))
    JarFile(dir.resolve("build/libs/sample.jar").toFile()).use { jar ->
      assertNotNull(jar.getJarEntry("META-INF/MANIFEST.MF"))
    }
    Files.writeString(script, """
      plugins { java; id("com.peco2282.obfaryn") }
      obfaryn { output { file = "build/result.jar"; mapping = "build/result.jar" } }
    """.trimIndent())
    assertTrue(runner.buildAndFail().output.contains("must use different paths"))
    assertFalse(Files.exists(dir.resolve("build/result.jar")))
  }

  @Test fun producesObfuscatedJarAndMapping() {
    val parentSource = dir.resolve("dependency-src/dep/Parent.java")
    Files.createDirectories(parentSource.parent)
    Files.writeString(parentSource, "package dep; public class Parent { public String inherited() { return \"parent\"; } }")
    val parentClasses = dir.resolve("dependency-classes")
    Files.createDirectories(parentClasses)
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
      "-d", parentClasses.toString(), parentSource.toString()))
    val dependency = dir.resolve("dependency.jar")
    JarOutputStream(Files.newOutputStream(dependency)).use { jar ->
      jar.putNextEntry(JarEntry("dep/Parent.class"))
      jar.write(Files.readAllBytes(parentClasses.resolve("dep/Parent.class")))
      jar.closeEntry()
    }
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"")
    Files.writeString(dir.resolve("build.gradle.kts"), """
      plugins {
        java
        id("com.peco2282.obfaryn")
      }
      dependencies { compileOnly(files("dependency.jar")) }
      obfaryn {
        platform = "generic"
        seed = 4L
        recomputeFrames = false
        rename { classes = true; methods = true; fields = true }
      }
    """.trimIndent())
    val source = dir.resolve("src/main/java/example/App.java")
    Files.createDirectories(source.parent)
    Files.writeString(source, "package example; public class App { public static String message() { return \"Secret\"; } }")
    Files.writeString(source.parent.resolve("Child.java"),
      "package example; public class Child extends dep.Parent { @Override public String inherited() { return super.inherited(); } public String own() { return \"own\"; } }")
    val result = GradleRunner.create().withProjectDir(dir.toFile()).withArguments("obfuscateJar", "--stacktrace")
      .withPluginClasspath().build()
    assertTrue(result.output.contains("BUILD SUCCESSFUL"))
    val output = dir.resolve("build/libs/sample-obfuscated.jar")
    assertTrue(Files.exists(output))
    val mapping = dir.resolve("build/libs/sample-obfuscated.mapping.txt")
    assertTrue(Files.exists(mapping))
    assertTrue(Files.readString(mapping).contains("example/Child.own()Ljava/lang/String; ->"))
    assertFalse(Files.readString(mapping).contains("example/Child.inherited()Ljava/lang/String; ->"))
    JarFile(output.toFile()).use { jar ->
      val contents = jar.entries().asSequence().filter { it.name.endsWith(".class") }
        .flatMap { jar.getInputStream(it).readBytes().asSequence() }.toList().toByteArray()
      assertFalse(String(contents, Charsets.ISO_8859_1).contains("Secret"))
    }
  }
}
