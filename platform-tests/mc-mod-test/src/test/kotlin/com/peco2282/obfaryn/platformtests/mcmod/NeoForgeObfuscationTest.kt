package com.peco2282.obfaryn.platformtests.mcmod

import com.peco2282.obfaryn.platformtests.mcmod.probe.Probe
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Same risk and approach as ForgeObfuscationTest, against NeoForge's standalone `bus` library
 * instead: a `@Mod` entry point (stable name) registers a `@SubscribeEvent` listener through a
 * real IEventBus, obfuscated via obfaryn-gradle-plugin + platforms:mc-mod, then a real event is
 * posted and the renamed listener's invocation is checked.
 */
class NeoForgeObfuscationTest {
  @TempDir lateinit var dir: Path

  @BeforeEach
  fun setUp() {
    Probe.reset()
  }

  @Test
  fun `obfuscated mod's renamed listener still receives the event`() {
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

    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"mc-mod-fixture\"")
    Files.writeString(
      dir.resolve("build.gradle.kts"),
      """
      plugins { java; id("com.peco2282.obfaryn") }
      dependencies { obfarynPlatform(files($platformFiles)) }
      obfaryn { platform = "neoforge"; seed = 1L }
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
    val modClass = loader.loadClass("com.peco2282.obfaryn.platformtests.mcmod.neoforge.NeoForgeMod")
    val mod = modClass.getDeclaredConstructor().newInstance()
    // Only NeoForgeMod's *class* name is kept stable; fireEvent() itself is an ordinary method
    // Obfaryn is free to rename, so find it by shape (the only no-arg void-returning public
    // method -- the `bus` property's generated getter is also no-arg and public, but returns
    // IEventBus) instead of by its original name.
    val fireEvent = modClass.declaredMethods.single {
      it.parameterCount == 0 && Modifier.isPublic(it.modifiers) && it.returnType == Void.TYPE
    }
    fireEvent.invoke(mod)

    assertTrue(Probe.events.contains("neoforge:test")) {
      "expected the obfuscated listener to record the event; got ${Probe.events}"
    }
  }
}
