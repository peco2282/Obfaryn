plugins {
  `java-library`
  `maven-publish`
}

repositories {
  mavenCentral()
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
  withSourcesJar()
  withJavadocJar()
}

publishing {
  publications {
    create<MavenPublication>("mavenJava") { from(components["java"]) }
  }
}
