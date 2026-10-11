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
        // kotlinx-rpc 0.11 gRPC DEV CHANNEL (decision recorded 2026-10-10). Stable 0.10.3+ on Maven
        // Central also carries the KRPC-560 fix, but moving to it is a version-number downgrade of the
        // whole client-server transport whose Kotlin/Native crash fix only the native CI lanes can prove.
        // Revisit when 0.11.x stable reaches Maven Central, then DELETE this redirector (and its twin in
        // dependencyResolutionManagement below).
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
        // kotlinx-rpc 0.11 gRPC DEV CHANNEL (decision recorded 2026-10-10). Stable 0.10.3+ on Maven
        // Central also carries the KRPC-560 fix, but moving to it is a version-number downgrade of the
        // whole client-server transport whose Kotlin/Native crash fix only the native CI lanes can prove.
        // Revisit when 0.11.x stable reaches Maven Central, then DELETE this redirector (and its twin in
        // dependencyResolutionManagement below).
        maven("https://redirector.kotlinlang.org/maven/kxrpc-grpc")
    }
}

includeBuild("tools/build-logic")

include(":app:androidApp")
include(":app:baselineprofile")
include(":app:sharedUI")
include(":contract")
include(":app:desktopApp")
include(":app:webApp")
include(":server")
include(":app:sharedLogic")
include(":tools:rpc-guard-ksp")
