import org.jetbrains.dokka.gradle.tasks.DokkaGenerateTask

plugins {
  `java-library`
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("org.jetbrains.dokka-javadoc")
  `maven-publish`
}
java { withSourcesJar() }
val dokkaJavadocJar = tasks.register<Jar>("dokkaJavadocJar") {
  group = JavaBasePlugin.DOCUMENTATION_GROUP
  description = "Creates a Javadoc jar from Dokka output"
  archiveClassifier.set("javadoc")
  from(tasks.named<DokkaGenerateTask>("dokkaGeneratePublicationJavadoc").flatMap { it.outputDirectory })
}
repositories { mavenCentral() }
// The kotlinx.serialization compiler plugin is not yet published for the Kotlin Gradle Plugin's
// own version (2.4.21, from root build.gradle.kts; see kotlin("compiler-embeddable") below, which
// resolves to that version too) -- only up to 2.2.0. Kept in its own configuration, resolved to a
// version that does match each other (2.1.21, the project's pinned language/API level), so
// SerializationCompatibilityTest can load an isolated, internally-consistent compiler + plugin
// pair via a dedicated classloader without changing testRuntimeClasspath's own resolved versions.
val serializationCompiler by configurations.creating
dependencies {
  api(project(":obfaryn-core"))
  api(project(":obfaryn-transformer"))
  implementation(project(":obfaryn-annotations"))
  implementation(libs.asm.commons)
  implementation(libs.asm.util)
  implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21")
  testImplementation(platform(libs.junit5.bom))
  testImplementation(kotlin("reflect"))
  testImplementation(kotlin("compiler-embeddable"))
  testImplementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
  serializationCompiler("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.21")
  serializationCompiler("org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.1.21")
  serializationCompiler(libs.kotlinx.serialization.json)
}
publishing { publications { create<MavenPublication>("mavenJava") { from(components["java"]); artifact(dokkaJavadocJar) } } }
tasks.test {
  useJUnitPlatform()
  systemProperty("obfaryn.serializationCompilerClasspath", serializationCompiler.asPath)
}
