plugins {
  java
  kotlin("jvm")
  application
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(project(":obfaryn-standard"))
  implementation(libs.picocli)
  testImplementation(project(":examples:custom-extension"))
  testImplementation(platform(libs.junit5.bom))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
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
