import java.util.Properties

rootProject.name = "Obfaryn"

pluginManagement {
  repositories {
    mavenLocal()
    gradlePluginPortal()
  }
}

// Uploads existing maven-publish publications (from build.gradle.kts) to Sonatype's Central
// Portal; does not create publications or apply maven-publish itself. Credentials: CENTRAL_USERNAME
// / CENTRAL_PASSWORD env vars, or centralUsername / centralPassword in (gitignored) secrets.properties
// -- the same Central Portal user token used for the existing distribution publish, not your login
// password. Run `./gradlew publishAggregationToCentralPortal` to upload (USER_MANAGED: release it
// from https://central.sonatype.com/ afterward), or `publishAggregationToCentralSnapshots` for a
// `-SNAPSHOT` version.
plugins {
  id("com.gradleup.nmcp.settings") version "1.6.2"
}
val centralSecrets = Properties().apply {
  val secretsFile = file("secrets.properties")
  if (secretsFile.isFile) secretsFile.inputStream().use { load(it) }
}
nmcpSettings {
  centralPortal {
    username = providers.environmentVariable("CENTRAL_USERNAME").orNull
      ?: centralSecrets.getProperty("centralUsername")
    password = providers.environmentVariable("CENTRAL_PASSWORD").orNull
      ?: centralSecrets.getProperty("centralPassword")
    publishingType = "USER_MANAGED"
  }
}

include("obfaryn-api")
include("obfaryn-core")
include("obfaryn-cli")
include("obfaryn-gradle-plugin")
include("obfaryn-annotations")
include("obfaryn-runtime")
include("obfaryn-transformer")

// external platform
include("platforms:paper")

include("obfaryn-standard")
include("examples:custom-extension")
