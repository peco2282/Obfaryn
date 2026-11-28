package com.peco2282.obfaryn.platformtests.mcmod

import com.peco2282.obfaryn.platformtests.mcmod.probe.Probe
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Fabric has no annotation-driven reflection discovery to break: the loader finds the entry
 * point by name (from fabric.mod.json) and just does Class.forName + no-arg constructor + call
 * onInitialize(). The risk worth checking here is narrower than Forge/NeoForge's -- only that
 * FabricPlatformProvider's entrypoint detection (which needs fabric-loader's own ModInitializer
 * class on the obfuscation's classpath to resolve the `isSubtypeOf` check) keeps the class name
 * stable and renaming doesn't break the ModInitializer interface implementation.
 */
class FabricObfuscationTest {
  @TempDir lateinit var dir: Path

  @BeforeEach
  fun setUp() {
    Probe.reset()
  }

  @Test
  fun `obfuscated mod's entrypoint is still found and still implements ModInitializer`() {
    val resources = dir.resolve("src/main/resources")
    Files.createDirectories(resources)
    JarFile(File(System.getProperty("obfaryn.fixtureJar"))).use { jar ->
      jar.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
        val target = resources.resolve(entry.name)
        Files.createDirectories(target.parent)
        jar.getInputStream(entry).use { input -> Files.copy(input, target) }
      }
    }

    val platformJars = System.getProperty("obfaryn.mcModPlatformClasspath").split(File.pathSeparator)
    platformJars.forEachIndexed { index, path -> Files.copy(Path.of(path), dir.resolve("platform-$index.jar")) }
    val platformFiles = platformJars.indices.joinToString(", ") { "\"platform-$it.jar\"" }

    // ModInitializer's class hierarchy must be resolvable from the obfuscation's own classpath
    // (dependencyClasspath in ObfarynPlugin.obfuscateJar) for FabricPlatformProvider's
    // isSubtypeOf check, so fabric-loader needs to be an actual dependency of this throwaway
    // project, not just copied alongside.
    val fabricLoaderJar = System.getProperty("obfaryn.fabricLoaderJar")
    Files.copy(Path.of(fabricLoaderJar), dir.resolve("fabric-loader.jar"))

    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"mc-mod-fixture\"")
    Files.writeString(
      dir.resolve("build.gradle.kts"),
      """
      plugins { java; id("com.peco2282.obfaryn") }
      dependencies {
        obfarynPlatform(files($platformFiles))
        compileOnly(files("fabric-loader.jar"))
      }
      obfaryn { platform = "fabric"; seed = 1L }
      """.trimIndent(),
    )

    val pluginClasspath = System.getProperty("obfaryn.pluginClasspath").split(File.pathSeparator).map(::File)
    val result = GradleRunner.create()
      .withProjectDir(dir.toFile())
      .withPluginClasspath(pluginClasspath)
      .withArguments("obfuscateJar", "--stacktrace")
      .build()

    val obfuscatedJar = dir.resolve("build/libs/mc-mod-fixture-obfuscated.jar").toFile()
    assertTrue(obfuscatedJar.isFile) { "expected obfuscated jar; build output:\n${result.output}" }

    val loader = java.net.URLClassLoader(arrayOf(obfuscatedJar.toURI().toURL()), javaClass.classLoader)
    val modClass = loader.loadClass("com.peco2282.obfaryn.platformtests.mcmod.fabric.FabricMod")
    val initializer = modClass.getDeclaredConstructor().newInstance() as net.fabricmc.api.ModInitializer
    initializer.onInitialize()

    assertTrue(Probe.events.contains("fabric:initialized")) {
      "expected the obfuscated entrypoint to run onInitialize(); got ${Probe.events}"
    }
  }
}
