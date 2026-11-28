// Obfuscation happens inside a throwaway GradleRunner (TestKit) project per test, exactly like
// obfaryn-gradle-plugin's own ObfarynPluginTest and platform-tests:paper-test: obfaryn-gradle-plugin
// and platforms:mc-mod are resolved as plain configurations and handed to
// GradleRunner.withPluginClasspath() / obfarynPlatform(files(...)) at test time, since a sibling
// project's compiled output cannot be put on this project's buildscript classpath directly.
plugins {
  kotlin("jvm")
}

repositories {
  mavenCentral()
  maven("https://maven.fabricmc.net/")
  maven("https://maven.minecraftforge.net/")
}

// Pinned independently of gradle/libs.versions.toml: these track Minecraft mod-loader releases,
// not this project's own dependency set.
val fabricLoaderVersion = "0.20.1"
val forgeEventbusVersion = "7.0.7"
val neoforgeBusVersion = "8.0.5"

sourceSets {
  val probe = create("probe")
  main {
    // Fixtures call into Probe to report observable side effects (see probe/Probe.kt); probe's
    // compiled output is never added to the `jar` task, so it is never fed to Obfaryn.
    compileClasspath += probe.output
    runtimeClasspath += probe.output
  }
  test {
    compileClasspath += probe.output
    runtimeClasspath += probe.output
  }
}

val obfarynPluginClasspath = configurations.create("obfarynPluginClasspath") { isCanBeConsumed = false }
val mcModPlatformClasspath = configurations.create("mcModPlatformClasspath") { isCanBeConsumed = false }
val fabricLoaderClasspath = configurations.create("fabricLoaderClasspath") { isCanBeConsumed = false }

dependencies {
  compileOnly("net.fabricmc:fabric-loader:$fabricLoaderVersion")
  compileOnly("net.minecraftforge:eventbus:$forgeEventbusVersion")
  compileOnly("net.neoforged:bus:$neoforgeBusVersion")

  testImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
  testImplementation("net.minecraftforge:eventbus:$forgeEventbusVersion")
  testImplementation("net.neoforged:bus:$neoforgeBusVersion")
  testImplementation(gradleTestKit())
  testImplementation(platform(libs.junit5.bom))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)

  obfarynPluginClasspath(project(":obfaryn-gradle-plugin"))
  mcModPlatformClasspath(project(":platforms:mc-mod"))
  fabricLoaderClasspath("net.fabricmc:fabric-loader:$fabricLoaderVersion")
}

configurations.all {
  // net.minecraftforge:eventbus pulls in an old, vulnerable log4j-api transitively.
  resolutionStrategy.force("org.apache.logging.log4j:log4j-api:2.25.5")
}

tasks.test {
  useJUnitPlatform()
  dependsOn(tasks.named("jar"))
  dependsOn(obfarynPluginClasspath)
  dependsOn(mcModPlatformClasspath)
  dependsOn(fabricLoaderClasspath)
  // Deliberately NOT the default sourceSets.test.runtimeClasspath: that includes
  // sourceSets.main.output (the *unobfuscated* fixture classes), which would let a renamed class
  // resolve to the original before the obfuscated jar is ever consulted.
  classpath = sourceSets["test"].output + sourceSets["probe"].output + configurations["testRuntimeClasspath"]
  doFirst {
    systemProperty("obfaryn.fixtureJar", tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath)
    systemProperty("obfaryn.pluginClasspath", obfarynPluginClasspath.asPath)
    systemProperty("obfaryn.mcModPlatformClasspath", mcModPlatformClasspath.asPath)
    systemProperty("obfaryn.fabricLoaderJar", fabricLoaderClasspath.files.single { it.name.startsWith("fabric-loader") }.absolutePath)
  }
}
