import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
  id("java")
  kotlin("jvm") version "2.4.21" apply false
}

group = "com.peco2282.obfaryn"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

java {
  toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

subprojects {
  val targetJava = when (name) {
//    "obfaryn-api", "obfaryn-annotations" -> 8
    "obfaryn-gradle-plugin" -> 17 to 17
    else -> 8 to 1.8
  }

  plugins.withId("java") {
    extensions.configure<JavaPluginExtension> {
      toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    tasks.withType<JavaCompile>().configureEach {
      options.release.set(targetJava.first)
    }
  }

  plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<KotlinJvmProjectExtension> {
      compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        jvmTarget.set(JvmTarget.fromTarget(targetJava.second.toString()))
        freeCompilerArgs.add("-Xjdk-release=${targetJava.first}")
      }
    }
    dependencies.add("implementation", libs.kotlin.stdlib)
  }
}

dependencies {
  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  useJUnitPlatform()
}
