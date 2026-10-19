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
  implementation("org.ow2.asm:asm-analysis:9.10.1")

  testImplementation(platform("org.junit:junit-bom:5.6.0"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
