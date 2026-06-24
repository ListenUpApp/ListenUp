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
    // detekt 2.0.0-alpha.5 publishes detekt-api's test-fixtures *sources* but NOT the
    // test-fixtures classes JAR; detekt-test's runtime variant requests that missing
    // `dev.detekt:detekt-api-test-fixtures` capability, so the dependency is excluded
    // here and the test-fixtures classes are supplied from detekt-test-utils instead.
    testImplementation("dev.detekt:detekt-test:${libs.versions.detekt.get()}") {
        exclude(group = "dev.detekt", module = "detekt-api")
    }
    testImplementation(libs.detekt.api)
    testImplementation("dev.detekt:detekt-test-utils:${libs.versions.detekt.get()}")
}

kotlin {
    jvmToolchain(21)
}
