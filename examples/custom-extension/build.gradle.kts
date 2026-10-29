plugins {
  `java-library`
  kotlin("jvm")
}
repositories { mavenCentral() }
dependencies { implementation(project(":obfaryn-api")) }
