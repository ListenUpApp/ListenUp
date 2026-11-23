rootProject.name = "listenup"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Minimum versions for vulnerable transitive JVM dependencies, shared with the build-logic build.
apply(from = "gradle/security-floors.settings.gradle.kts")

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

includeBuild("tools/build-logic")

include(":app:androidApp")
include(":app:baselineprofile")
include(":app:storeshots")
include(":app:sharedUI")
include(":contract")
include(":app:desktopApp")
include(":app:webApp")
include(":server")
include(":app:sharedLogic")
include(":tools:rpc-guard-ksp")
