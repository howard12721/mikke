import org.gradle.api.initialization.resolve.RepositoriesMode

rootProject.name = "mikke-backend"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    ":api",
    ":platform",
    ":events:event-core",
    ":events:post-events",
    ":events:friendship-events",
    ":events:user-events",
    ":events:guess-events",
    ":services:identity-service",
    ":services:friendship-service",
    ":services:post-service",
    ":services:media-service",
    ":services:guess-service",
    ":services:notification-service",
)
