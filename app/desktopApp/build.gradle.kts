import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("listenup.jvm")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        // :app:sharedLogic/:app:sharedUI carry pre-release-marked metadata via the kotlinx-rpc dev-channel pin (see gradle/libs.versions.toml); drop this flag when migrating to rpc stable.
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }
}

dependencies {
    implementation(projects.app.sharedUI)
    implementation(projects.app.sharedLogic)

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.androidx.material.icons.extended)

    // Lifecycle (needed for ViewModel supertype from :app:sharedUI)
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

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
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
                iconFile.set(project.file("src/main/resources/icon.png"))
                packageName = "listenup"
                debMaintainer = "support@calypsan.com"
                menuGroup = "AudioVideo"
                appRelease = "1"
                appCategory = "Audio"
            }

            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
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
