pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Kotlin 2.4 class metadata needs R8/D8 9.1.29 (AGP 8.13 bundles 8.13, which only
// understands Kotlin <= 2.3). Without this, D8 logs a "Unexpected error during rewriting of
// Kotlin metadata" stack trace for every CloudStream class and the build runs out of memory.
buildscript {
    repositories {
        google()
        mavenCentral()
        // R8 9.1.29 is not on Google Maven; official R8 release repository
        maven { url = uri("https://storage.googleapis.com/r8-releases/raw") }
    }
    dependencies {
        classpath("com.android.tools:r8:9.1.29")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri(rootDir.resolve("local-m2")) }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Kestrel"

include(":app")
include(":core")
include(":domain")
include(":data")
include(":plugin-host")
include(":feature-home")
include(":feature-search")
include(":feature-details")
include(":feature-player")
include(":feature-extensions")
include(":feature-library")
include(":feature-settings")
include(":feature-manga")
