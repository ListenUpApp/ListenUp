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
        // JetBrains Compose dev repository for alpha libraries (Navigation 3, Material 3 Adaptive)
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

include(":androidApp")
include(":baselineprofile")
include(":composeApp")
include(":desktopApp")
include(":server")
include(":shared")
include(":build-logic:detekt-rules")
include(":rpc-guard-ksp")
