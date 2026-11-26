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
