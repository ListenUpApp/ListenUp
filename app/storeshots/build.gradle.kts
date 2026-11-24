// Store-screenshot capture: a UiAutomator suite that drives the debug app through the screens the Play
// listing shows. Never part of CI — nothing depends on this module and no CI job runs its instrumentation;
// tools/scripts/store-screenshots-android.sh builds it and runs it on an emulator against a seeded rig server.
plugins {
    alias(libs.plugins.androidTest)
    id("listenup.android.defaults")
}

android {
    namespace = "com.calypsan.listenup.storeshots"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app:androidApp"

    // The suite drives the app from outside, as a listener would: it instruments itself, not the app,
    // so the app runs exactly as installed.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
