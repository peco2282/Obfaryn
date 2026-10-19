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

repositories {
  mavenCentral()
}

dependencies {
  api(project(":obfaryn-api"))
  implementation(libs.asm.analysis)

  testImplementation(platform(libs.junit5.bom))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  useJUnitPlatform()
}

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      from(components["java"])
    }
  }
  repositories {
    mavenLocal()
  }
}
