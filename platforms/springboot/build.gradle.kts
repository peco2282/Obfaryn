import org.jetbrains.dokka.gradle.tasks.DokkaGenerateTask

plugins {
  `java-library`
  kotlin("jvm")
  id("org.jetbrains.dokka")
  id("org.jetbrains.dokka-javadoc")
  `maven-publish`
}

base {
  archivesName.set("obfaryn-platform-springboot")
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
  compileOnly(project(":obfaryn-platform-kit"))

  testImplementation(project(":obfaryn-standard"))
  testImplementation(platform(libs.junit5.bom))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(project(":obfaryn-platform-kit"))
  testRuntimeOnly(libs.junit.platform.launcher)
}

val platformKitJar = project(":obfaryn-platform-kit").tasks.named<Jar>("jar")
tasks.jar {
  from(platformKitJar.map { zipTree(it.archiveFile.get().asFile) }) {
    include("com/peco2282/obfaryn/platformkit/**")
  }
}

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      artifactId = "obfaryn-platform-springboot"
      from(components["java"])
      artifact(dokkaJavadocJar)
    }
  }
}

tasks.test {
  useJUnitPlatform()
}
