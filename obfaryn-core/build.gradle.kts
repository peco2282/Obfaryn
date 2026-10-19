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

  implementation("org.ow2.asm:asm:9.10.1")
  implementation("org.ow2.asm:asm-tree:9.10.1")
  implementation("org.ow2.asm:asm-commons:9.10.1")
  implementation("org.ow2.asm:asm-util:9.10.1")

  testImplementation(platform("org.junit:junit-bom:5.6.0"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
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

