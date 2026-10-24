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

rootProject.name = "Clippr"

include(
    ":app",
    ":core:model",
    ":core:database",
    ":core:media",
    ":core:render",
    ":core:ui",
    ":feature:home",
    ":feature:editor",
    ":feature:timeline",
    ":feature:inspector",
    ":feature:export",
)
