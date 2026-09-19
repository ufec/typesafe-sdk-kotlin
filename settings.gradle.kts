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
    }
}

rootProject.name = "typesafe-sdk-kotlin"

// Runnable demonstration of the library, in the spirit of the upstream
// JavaScript SDK's `npm run demo`. It is a separate project so it can never be
// mistaken for part of the published artifact, and so the root project stays a
// single publishable module.
include(":examples")
