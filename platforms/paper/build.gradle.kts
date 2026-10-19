import org.jetbrains.dokka.gradle.tasks.DokkaGenerateTask

plugins {
  `java-library`
  kotlin("jvm")
  id("org.jetbrains.dokka") version "2.2.0"
  id("org.jetbrains.dokka-javadoc") version "2.2.0"
  `maven-publish`
}

group = "com.peco2282.obfaryn"
version = "0.0.0-SNAPSHOT"

base {
  archivesName.set("obfaryn-platform-paper")
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
  compileOnly(project(":obfaryn-api"))

  testImplementation(project(":obfaryn-core"))
  testImplementation(platform(libs.junit5.bom))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      artifactId = "obfaryn-platform-paper"
      from(components["java"])
      artifact(dokkaJavadocJar)
    }
  }
  repositories {
    mavenLocal()
  }
}

tasks.test {
  useJUnitPlatform()
}
