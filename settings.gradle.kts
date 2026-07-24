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
        // kotlinx-rpc gRPC DEV CHANNEL — supplies the KRPC-560 fix (0.11.0-grpc-188).
        // MIGRATE to kotlinx-rpc 0.11.x stable on Maven Central once it ships with the KRPC-560 fix,
        // then DELETE this redirector (and its twin in dependencyResolutionManagement below).
        maven("https://redirector.kotlinlang.org/maven/kxrpc-grpc")
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
        // kotlinx-rpc gRPC DEV CHANNEL — supplies the KRPC-560 fix (0.11.0-grpc-188).
        // MIGRATE to kotlinx-rpc 0.11.x stable on Maven Central once it ships with the KRPC-560 fix,
        // then DELETE this redirector (and its twin in pluginManagement above).
        maven("https://redirector.kotlinlang.org/maven/kxrpc-grpc")
    }
}

includeBuild("tools/build-logic")

include(":app:androidApp")
include(":app:baselineprofile")
include(":app:sharedUI")
include(":contract")
include(":app:desktopApp")
include(":server")
include(":app:sharedLogic")
include(":tools:rpc-guard-ksp")
