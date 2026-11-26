// Obfuscation happens inside a throwaway GradleRunner (TestKit) project per test, exactly like
// obfaryn-gradle-plugin's own ObfarynPluginTest: a sibling project's compiled output cannot be put
// on this project's buildscript classpath directly (Gradle rejects project dependencies there), so
// obfaryn-gradle-plugin and platforms:paper are instead resolved as plain configurations and handed
// to GradleRunner.withPluginClasspath() / obfarynPlatform(files(...)) at test time.
plugins {
  kotlin("jvm")
}

repositories {
  mavenCentral()
  maven("https://repo.papermc.io/repository/maven-public/")
}

// Pinned independently of gradle/libs.versions.toml: these track a Minecraft/Paper release, not
// this project's own dependency set, and MockBukkit only ships per-Paper-version artifacts.
// Must match what this MockBukkit release was built/tested against (its own POM pins
// paper-api:1.21.1-R0.1-SNAPSHOT); a newer Paper API's Material/tag data can disagree with
// MockBukkit's bundled fixture data in ways unrelated to Obfaryn (observed: InternalTag
// rejecting "minecraft:chain" at 1.21.11).
val paperApiVersion = "1.21.1-R0.1-SNAPSHOT"
val mockBukkitVersion = "3.133.2"

sourceSets {
  val probe = create("probe")
  main {
    // The fixture plugin calls into Probe to report observable side effects (see probe/Probe.kt);
    // probe's compiled output is never added to the `jar` task, so it is never fed to Obfaryn.
    compileClasspath += probe.output
    runtimeClasspath += probe.output
  }
  test {
    // The test reads Probe.events directly, after the obfuscated jar (loaded by MockBukkit) calls
    // into the very same Probe class on this shared classpath.
    compileClasspath += probe.output
    runtimeClasspath += probe.output
  }
}

val obfarynPluginClasspath = configurations.create("obfarynPluginClasspath") { isCanBeConsumed = false }
val paperPlatformClasspath = configurations.create("paperPlatformClasspath") { isCanBeConsumed = false }

dependencies {
  compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

  testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
  testImplementation("com.github.seeseemelk:MockBukkit-v1.21:$mockBukkitVersion")
  testImplementation(gradleTestKit())
  testImplementation(platform(libs.junit5.bom))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)

  obfarynPluginClasspath(project(":obfaryn-gradle-plugin"))
  paperPlatformClasspath(project(":platforms:paper"))
}

tasks.test {
  useJUnitPlatform()
  dependsOn(tasks.named("jar"))
  dependsOn(obfarynPluginClasspath)
  dependsOn(paperPlatformClasspath)
  // Deliberately NOT the default sourceSets.test.runtimeClasspath: that includes
  // sourceSets.main.output (the *unobfuscated* fixture classes). With it present, MockBukkit's
  // parent-first plugin classloader resolves SamplePlugin from this test JVM's own classpath
  // before ever looking inside the obfuscated jar it was asked to load, so the obfuscated jar's
  // class never actually gets defined by a PluginClassLoader and JavaPlugin's constructor rejects
  // it -- silently testing nothing.
  classpath = sourceSets["test"].output + sourceSets["probe"].output + configurations["testRuntimeClasspath"]
  doFirst {
    systemProperty("obfaryn.fixtureJar", tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath)
    systemProperty("obfaryn.pluginClasspath", obfarynPluginClasspath.asPath)
    systemProperty("obfaryn.paperPlatformClasspath", paperPlatformClasspath.asPath)
  }
}
