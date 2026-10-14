import org.jetbrains.dokka.gradle.tasks.DokkaGenerateTask

plugins {
  java
  kotlin("jvm")
  id("org.jetbrains.dokka") version "2.2.0"
  id("org.jetbrains.dokka-javadoc") version "2.2.0"
  `java-gradle-plugin`
  `maven-publish`
}

group = "com.peco2282.obfaryn"
version = "0.0.0-SNAPSHOT"

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
  implementation(project(":obfaryn-core"))
  testImplementation(gradleTestKit())

  testImplementation(platform("org.junit:junit-bom:6.0.0"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
  repositories {
    mavenLocal()
  }
}


tasks.test {
  useJUnitPlatform()
}
