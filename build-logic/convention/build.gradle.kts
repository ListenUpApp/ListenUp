plugins {
    `kotlin-dsl`
}

dependencies {
    // Put KGP + the AGP KMP-library plugin on the convention classpath so the
    // precompiled script plugins can apply them by id.
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.android.kmpLibrary.gradlePlugin)

    // Runtime JSON parsing for the localization generator (parseToJsonElement;
    // no serialization compiler plugin needed).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}
