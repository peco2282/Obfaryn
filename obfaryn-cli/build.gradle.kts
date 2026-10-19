plugins {
  java
  kotlin("jvm")
  application
}

group = "com.peco2282.obfaryn"
version = "0.0.0-SNAPSHOT"

repositories {
  mavenCentral()
}

dependencies {
  implementation(project(":obfaryn-core"))
  implementation("info.picocli:picocli:4.7.7")
  testImplementation(platform("org.junit:junit-bom:5.6.0"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
  mainClass.set("com.peco2282.obfaryn.cli.MainKt")
  applicationName = "obfaryn"
}

tasks.jar {
  manifest.attributes["Main-Class"] = "com.peco2282.obfaryn.cli.MainKt"
}

tasks.test {
  useJUnitPlatform()
}
