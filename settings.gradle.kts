rootProject.name = "Obfaryn"

pluginManagement {
  repositories {
    mavenLocal()
    gradlePluginPortal()
  }
}

include("obfaryn-api")
include("obfaryn-core")
include("obfaryn-cli")
include("obfaryn-gradle-plugin")
include("obfaryn-annotations")

// external platform
include("platforms:paper")
