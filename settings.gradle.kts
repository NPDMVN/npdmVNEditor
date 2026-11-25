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
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "dmedit"

include(":app")
include(":core:model")
include(":core:native-bridge")
include(":core:shell")
include(":feature:explorer")
include(":feature:editor")
include(":feature:backups")
include(":feature:spoofer")
include(":feature:settings")
