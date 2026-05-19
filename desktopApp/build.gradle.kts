import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-Xexpect-actual-classes",
                "-Xreturn-value-checker=check",
                "-Xexplicit-backing-fields",
                "-Xskip-prerelease-check", // Skip pre-release Kotlin metadata check
            )
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":sharedLogic"))
            implementation(project(":composeApp"))

            implementation(compose.desktop.currentOs)
            implementation(libs.compose.material3)
            implementation(libs.androidx.material.icons.extended)

            // Lifecycle (needed for ViewModel supertype from composeApp)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            // Global media key support
            implementation(libs.jnativehook)

            // Koin
            implementation(libs.koin.core)
            implementation(libs.koin.compose)

            // Logging
            implementation(libs.kotlin.logging)
            implementation(libs.logback.classic)

            // KMPalette for dynamic color extraction from cover art
            implementation(libs.kmpalette.core)

            // Coroutines
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.calypsan.listenup.desktop.MainKt"

        jvmArgs +=
            listOf(
                "-Xmx512m",
                "-Dfile.encoding=UTF-8",
            )

        nativeDistributions {
            targetFormats(
                TargetFormat.Deb, // Debian/Ubuntu
                TargetFormat.Rpm, // Fedora/RHEL
                TargetFormat.Msi, // Windows
                TargetFormat.Exe, // Windows portable
            )

            packageName = "ListenUp"
            packageVersion = "1.0.0"
            description = "ListenUp Audiobook Client"
            copyright = "2025 Calypsan"
            vendor = "Calypsan"

            linux {
                iconFile.set(project.file("src/jvmMain/resources/icon.png"))
                packageName = "listenup"
                debMaintainer = "support@calypsan.com"
                menuGroup = "AudioVideo"
                appRelease = "1"
                appCategory = "Audio"
            }

            windows {
                iconFile.set(project.file("src/jvmMain/resources/icon.ico"))
                menuGroup = "ListenUp"
                dirChooser = true
                perUserInstall = true
                shortcut = true
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }
        }

        buildTypes.release {
            proguard {
                isEnabled.set(false) // Enable later for size optimization
            }
        }
    }
}
