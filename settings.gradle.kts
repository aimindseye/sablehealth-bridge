pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
            }
            filter {
                includeVersion("org.robolectric", "android-all-instrumented", "14-robolectric-10818077-i7")
                includeVersion("org.robolectric", "android-all-instrumented", "16-robolectric-13921718-i7")
            }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "sablehealth-bridge"
include(":core", ":app")
