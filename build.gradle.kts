import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.Properties

plugins {
  id("java")
  kotlin("jvm") version "2.4.21" apply false
}

group = "com.peco2282.obfaryn"
version = providers.gradleProperty("releaseVersion").getOrElse("0.0.0-SNAPSHOT")

val publishingSecrets = Properties().apply {
  val secretsFile = rootProject.file("secrets.properties")
  if (secretsFile.isFile) secretsFile.inputStream().use { load(it) }
}
val publishingUrlKey = if (version.toString().contains("snapshot", ignoreCase = true)) "snapshotUrl" else "releaseUrl"
val publishingUrl = providers.gradleProperty("publishRepositoryUrl").orNull
  ?: publishingSecrets.getProperty(publishingUrlKey)?.also {
    require(it.isNotBlank()) { "secrets.properties: $publishingUrlKey must not be blank" }
  }
  ?: if (rootProject.file("secrets.properties").isFile) {
    error("secrets.properties: $publishingUrlKey is required")
  } else null

repositories {
  mavenCentral()
}

java {
  toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

subprojects {
  group = rootProject.group
  version = rootProject.version

  plugins.withId("maven-publish") {
    extensions.configure<PublishingExtension> {
      publications.withType<MavenPublication>().configureEach {
        pom {
          name.set(project.name)
          description.set("Customizable Kotlin/JVM and Java JAR obfuscation: ${project.name}")
          url.set("https://github.com/peco2282/Obfaryn")
          licenses {
            license {
              name.set("Mozilla Public License 2.0")
              url.set("https://www.mozilla.org/en-US/MPL/2.0/")
              distribution.set("repo")
            }
          }
          developers {
            developer {
              id.set("peco2282")
              name.set("peco2282")
              url.set("https://github.com/peco2282")
            }
          }
          scm {
            url.set("https://github.com/peco2282/Obfaryn")
            connection.set("scm:git:https://github.com/peco2282/Obfaryn.git")
            developerConnection.set("scm:git:ssh://git@github.com/peco2282/Obfaryn.git")
          }
        }
      }
      repositories {
        maven {
          name = "distribution"
          url = uri(publishingUrl
            ?: rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI().toString())
          if (url.scheme == "https") {
            credentials {
              username = publishingSecrets.getProperty("user") ?: providers.environmentVariable("MAVEN_USERNAME").orNull
              password = publishingSecrets.getProperty("password") ?: providers.environmentVariable("MAVEN_PASSWORD").orNull
            }
          }
        }
      }
    }
    if (providers.environmentVariable("SIGNING_KEY").isPresent) {
      pluginManager.apply("signing")
      extensions.configure<SigningExtension> {
        useInMemoryPgpKeys(
          providers.environmentVariable("SIGNING_KEY").get(),
          providers.environmentVariable("SIGNING_PASSWORD").orNull,
        )
        sign(project.extensions.getByType<PublishingExtension>().publications)
      }
    }
  }

  tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) { into("META-INF") }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
  }

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
