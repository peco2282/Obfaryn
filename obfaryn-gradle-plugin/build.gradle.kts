import org.jetbrains.dokka.gradle.tasks.DokkaGenerateTask

plugins {
  java
  kotlin("jvm")
  id("org.jetbrains.dokka") version "2.2.0"
  id("org.jetbrains.dokka-javadoc") version "2.2.0"
  `java-gradle-plugin`
  `maven-publish`
}

java {
  withSourcesJar()
}

kotlin {
  jvmToolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
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
  implementation(gradleApi())
  implementation(project(":obfaryn-standard"))
  testImplementation(gradleTestKit())

  testImplementation(platform(libs.junit6.bom))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
  plugins {
    create("obfaryn") {
      id = "com.peco2282.obfaryn"
      implementationClass = "com.peco2282.obfaryn.gradle.ObfarynPlugin"
    }
  }
}

publishing {
  publications.withType<MavenPublication>().configureEach {
    if (name == "pluginMaven") {
      artifact(dokkaJavadocJar)
    }
  }
}


val paperPlatformFixture by configurations.creating {
  isCanBeConsumed = false
}
dependencies {
  paperPlatformFixture(project(":platforms:paper"))
}

tasks.test {
  dependsOn(paperPlatformFixture)
  doFirst {
    systemProperty("obfaryn.test.paperClasspath", paperPlatformFixture.asPath)
  }
  useJUnitPlatform()
}

val extensionFixture = configurations.create("extensionFixture") { isCanBeConsumed = false }
dependencies { add(extensionFixture.name, project(":examples:custom-extension")) }
tasks.test {
  dependsOn(extensionFixture)
  doFirst { systemProperty("obfaryn.test.extensionClasspath", extensionFixture.asPath) }
}
