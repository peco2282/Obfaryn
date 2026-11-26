package com.peco2282.obfaryn.platformtests.paper

import be.seeseemelk.mockbukkit.MockBukkit
import com.peco2282.obfaryn.platformtests.paper.probe.Probe
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Builds a real Paper plugin jar (the `fixture` compiled under src/main), obfuscates it through
 * obfaryn-gradle-plugin + platforms:paper exactly as a real Gradle-plugin consumer would (via a
 * throwaway TestKit project, not by calling the engine directly), then loads the *obfuscated*
 * result through MockBukkit as a real server would and checks that an @EventHandler listener
 * method Obfaryn is free to rename still gets discovered and invoked. This is the specific risk
 * this suite exists to catch: Bukkit finds event handlers by reflecting over the registered
 * instance, not by name, so renaming could silently break annotation retention or descriptor
 * matching without the obfuscated plugin ever throwing.
 */
class PaperObfuscationTest {
  @TempDir lateinit var dir: Path

  @BeforeEach
  fun setUp() {
    MockBukkit.mock()
    Probe.reset()
  }

  @AfterEach
  fun tearDown() {
    MockBukkit.unmock()
  }

  @Test
  fun `obfuscated plugin's renamed listener still receives the join event`() {
    // Re-explode the already-compiled fixture jar into the temp project's resources, so its own
    // `jar` task reassembles an identical input without recompiling Kotlin inside TestKit.
    val resources = dir.resolve("src/main/resources")
    Files.createDirectories(resources)
    JarFile(File(System.getProperty("obfaryn.fixtureJar"))).use { jar ->
      jar.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
        val target = resources.resolve(entry.name)
        Files.createDirectories(target.parent)
        jar.getInputStream(entry).use { input -> Files.copy(input, target) }
      }
    }

    val paperPlatformJars = System.getProperty("obfaryn.paperPlatformClasspath").split(File.pathSeparator)
    paperPlatformJars.forEachIndexed { index, path -> Files.copy(Path.of(path), dir.resolve("platform-$index.jar")) }
    val platformFiles = paperPlatformJars.indices.joinToString(", ") { "\"platform-$it.jar\"" }

    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"paper-fixture\"")
    Files.writeString(
      dir.resolve("build.gradle.kts"),
      """
      plugins { java; id("com.peco2282.obfaryn") }
      dependencies { obfarynPlatform(files($platformFiles)) }
      obfaryn { platform = "paper"; seed = 1L }
      """.trimIndent(),
    )

    val pluginClasspath = System.getProperty("obfaryn.pluginClasspath").split(File.pathSeparator).map(::File)
    val result = GradleRunner.create()
      .withProjectDir(dir.toFile())
      .withPluginClasspath(pluginClasspath)
      .withArguments("obfuscateJar", "--stacktrace")
      .build()

    val obfuscatedJar = dir.resolve("build/libs/paper-fixture-obfuscated.jar").toFile()
    assertTrue(obfuscatedJar.isFile) { "expected obfuscated jar; build output:\n${result.output}" }

    // loadJar only instantiates the plugin and calls onLoad(); registering the @EventHandler
    // listener happens in onEnable(), which requires enabling it explicitly.
    val plugin = MockBukkit.loadJar(obfuscatedJar)
    MockBukkit.getMock()!!.pluginManager.enablePlugin(plugin)
    MockBukkit.getMock()!!.addPlayer("Steve")

    assertTrue(Probe.events.contains("join:Steve")) {
      "expected the obfuscated listener to record the join; got ${Probe.events}"
    }
  }
}
