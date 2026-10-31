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
        google()
        mavenCentral()
    }
}

rootProject.name = "Tool-Hubs"

include(":core:designsystem")
include(":core:ui")
include(":core:common")
include(":tools:chime")
include(":tools:network-switcher")
include(":tools:data-warden")
