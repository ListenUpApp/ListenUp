// Version is sourced from the VERSION file at the repo root (single source of truth for marketing version).
fun versionName(): String =
    rootProject.layout.projectDirectory
        .file("VERSION")
        .asFile
        .takeIf { it.exists() }
        ?.readText()
        ?.trim()
        ?.removePrefix("v")
        ?.ifEmpty { "0.0.1" }
        ?: "0.0.1"

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
        versionName = versionName()
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
            // Always require the real release signing config. If KEYSTORE_FILE is not set the
            // storeFile is null and AGP will fail loud during packaging — which is the correct
            // behaviour for a production release build.
            signingConfig = signingConfigs.getByName("release")
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

// The Baseline Profile plugin generates an APK for the nonMinifiedRelease and benchmarkRelease
// variants. These variants derive from "release" so they inherit the release signingConfig, but
// they only run locally — there is no CI keystore available for them. Reassign those two variants
// to the debug signing config so local profile generation works keyless, while the shippable
// "release" variant (above) keeps the real config and fails loud if the keystore is absent.
@Suppress("UnstableApiUsage")
androidComponents {
    val debugConfig = android.signingConfigs.getByName("debug")
    onVariants(selector().withBuildType("nonMinifiedRelease")) { variant ->
        variant.signingConfig?.setConfig(debugConfig)
    }
    onVariants(selector().withBuildType("benchmarkRelease")) { variant ->
        variant.signingConfig?.setConfig(debugConfig)
    }
}

dependencies {
    implementation(projects.sharedUI)
    // SLF4J Android backend - routes kotlin-logging to Logcat
    implementation(libs.slf4j.android)

    // ProfileInstaller — allows the Baseline Profile to be installed at app startup
    // on Android 7+ (Nougat) without requiring a full Play Store delivery pass.
    implementation(libs.androidx.profileinstaller)

    // Baseline Profile — wires the :app:baselineprofile generator so the release build
    // embeds the generated baseline-prof.txt. The AGP plugin handles merging.
    baselineProfile(project(":app:baselineprofile"))
}
