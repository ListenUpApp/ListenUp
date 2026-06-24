plugins {
    // Version is declared once at the build-logic root (apply false); applying it here without a
    // version keeps the Kotlin Gradle plugin from being loaded with an explicit version in more
    // than one subproject.
    id("org.jetbrains.kotlin.jvm")
}

group = "com.calypsan.listenup.build-logic"
version = "0.0.1"

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.api)
    testImplementation(kotlin("test"))
    testImplementation("dev.detekt:detekt-test:${libs.versions.detekt.get()}")
}

kotlin {
    jvmToolchain(21)
}
