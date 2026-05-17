// Auto-version from git tags (e.g. v0.1.0 -> versionName "0.1.0", versionCode from commit count)
fun gitVersionName(): String =
    try {
        providers
            .exec {
                commandLine("git", "describe", "--tags", "--abbrev=0")
                isIgnoreExitValue = true
            }.standardOutput.asText
            .get()
            .trim()
            .removePrefix("v")
            .ifEmpty { "0.0.1" }
    } catch (_: Exception) {
        "0.0.1"
    }

fun gitVersionCode(): Int =
    try {
        providers
            .exec {
                commandLine("git", "rev-list", "--count", "HEAD")
                isIgnoreExitValue = true
            }.standardOutput.asText
            .get()
            .trim()
            .toIntOrNull() ?: 1
    } catch (_: Exception) {
        1
    }

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.calypsan.listenup.client"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.calypsan.listenup.client"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = gitVersionCode()
        versionName = gitVersionName()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    signingConfigs {
        create("release") {
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null && file(ksFile).exists()) {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Use the release signing config when available (CI), otherwise fall back to
            // debug signing so the nonMinifiedRelease variant (used for Baseline Profile
            // generation) can be packaged locally without a keystore.
            val releaseSigningConfig = signingConfigs.getByName("release")
            signingConfig =
                if (releaseSigningConfig.storeFile != null) {
                    releaseSigningConfig
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
        htmlReport = true
        xmlReport = true
        sarifReport = true
    }
}

dependencies {
    implementation(project(":composeApp"))
    // SLF4J Android backend - routes kotlin-logging to Logcat
    implementation(libs.slf4j.android)

    // ProfileInstaller — allows the Baseline Profile to be installed at app startup
    // on Android 7+ (Nougat) without requiring a full Play Store delivery pass.
    implementation(libs.androidx.profileinstaller)

    // Baseline Profile — wires the :baselineprofile generator so the release build
    // embeds the generated baseline-prof.txt. The AGP plugin handles merging.
    baselineProfile(project(":baselineprofile"))
}
