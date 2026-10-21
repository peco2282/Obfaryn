import org.jetbrains.dokka.gradle.tasks.DokkaGenerateTask

plugins {
  `java-library`
  kotlin("jvm")
  id("org.jetbrains.dokka") version "2.2.0"
  id("org.jetbrains.dokka-javadoc") version "2.2.0"
  `maven-publish`
}

java {
  withSourcesJar()
}

val dokkaJavadocJar = tasks.register<Jar>("dokkaJavadocJar") {
  group = JavaBasePlugin.DOCUMENTATION_GROUP
  description = "Creates a Javadoc jar from Dokka output"
  archiveClassifier.set("javadoc")
  from(tasks.named<DokkaGenerateTask>("dokkaGeneratePublicationJavadoc").flatMap { it.outputDirectory })
}

repositories {
  mavenCentral()
}

dependencies {
  api(project(":obfaryn-api"))
  api(project(":obfaryn-transformer"))
  implementation(project(":obfaryn-annotations"))

  implementation(libs.asm)
  implementation(libs.asm.tree)
  implementation(libs.asm.commons)
  implementation(libs.asm.util)
  implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21")

  testImplementation(platform(libs.junit5.bom))
  testImplementation(kotlin("reflect"))
  testImplementation(kotlin("compiler-embeddable"))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      from(components["java"])
      artifact(dokkaJavadocJar)
    }
  }
}

tasks.test {
  useJUnitPlatform()
}

