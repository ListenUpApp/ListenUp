plugins {
    alias(libs.plugins.androidTest)
    // Note: org.jetbrains.kotlin.android is NOT applied here — AGP 9.0+ has
    // built-in Kotlin support and rejects the external plugin (see kotl.in/gradle/agp-built-in-kotlin).
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.calypsan.listenup.baselineprofile"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Point at the app module that will consume the generated profile.
    targetProjectPath = ":app:androidApp"

    // Gradle-managed device: API 34 AOSP ATD image — rooted by default, so
    // generation works without requiring a connected physical device.
    // API 34 is the highest level that ships a stable `aosp-atd` system image;
    // API 35+ images use `google-atd` which requires a sign-in. Use API 34
    // to keep generation fully hermetic and CI-friendly.
    // AGP 9.x DSL: localDevices (replaces the old devices { create<ManagedVirtualDevice> } syntax).
    testOptions.managedDevices.localDevices {
        create("pixel6Api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp-atd"
        }
    }
}

baselineProfile {
    // Run generation on the managed device above — no connected device needed.
    managedDevices += "pixel6Api34"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
