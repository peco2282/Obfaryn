package com.peco2282.obfaryn.platformtests.mcmod

import com.peco2282.obfaryn.platformtests.mcmod.probe.Probe
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.invoke.MethodHandles
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Builds a real Forge mod jar (a `@Mod` entry point registering a `@SubscribeEvent` listener via
 * the real net.minecraftforge:eventbus library), obfuscates it through obfaryn-gradle-plugin +
 * platforms:mc-mod exactly as a real Gradle-plugin consumer would, then posts a real event through
 * a real BusGroup/EventBus and checks the renamed listener method still gets invoked. This is the
 * specific risk this suite exists to catch: the event bus discovers @SubscribeEvent handlers by
 * reflecting over whatever instance was registered, not by name, so renaming could silently break
 * annotation retention or descriptor matching without the obfuscated mod ever throwing -- it is
 * exactly this class of bug that caught eventbus 7.x's relocated SubscribeEvent annotation.
 */
class ForgeObfuscationTest {
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
      obfaryn { platform = "forge"; seed = 1L }
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

    val loader = URLClassLoader(arrayOf(obfuscatedJar.toURI().toURL()), javaClass.classLoader)
    val modClass = loader.loadClass("com.peco2282.obfaryn.platformtests.mcmod.forge.ForgeMod")
    val mod = modClass.getDeclaredConstructor().newInstance()
    // Only ForgeMod's *class* name is kept stable (the entrypoint contract); fireEvents() itself
    // is an ordinary method Obfaryn is free to rename, so find it by shape (the only no-arg
    // void-returning public method) instead of by its original name.
    val fireEvents = modClass.declaredMethods.single {
      it.parameterCount == 0 && java.lang.reflect.Modifier.isPublic(it.modifiers) && it.returnType == Void.TYPE
    }
    fireEvents.invoke(mod)

    assertTrue(Probe.events.contains("forge:test")) {
      "expected the obfuscated listener to record the test event; got ${Probe.events}"
    }
    assertTrue(Probe.events.contains("forge:other")) {
      "expected the obfuscated listener to record the other event; got ${Probe.events}"
    }
  }
}
