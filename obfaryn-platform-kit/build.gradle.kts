import org.jetbrains.dokka.gradle.tasks.DokkaGenerateTask

plugins {
  `java-library`
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("org.jetbrains.dokka-javadoc")
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

  testImplementation(platform(libs.junit5.bom))
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
