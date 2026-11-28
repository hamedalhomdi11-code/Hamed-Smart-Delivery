pluginManagement {
    repositories {
        google()
        // Maven Central mirror avoids shared-egress HTTP 429 throttling on hosted builders.
        maven { url = uri("https://maven-central-asia.storage-download.googleapis.com/maven2/") }
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Render build region is Singapore; use Google's Asia-Pacific Maven Central mirror first.
        maven { url = uri("https://maven-central-asia.storage-download.googleapis.com/maven2/") }
        mavenCentral()
    }
}
rootProject.name = "HamedSmartDelivery"
include(":app", ":admin")
