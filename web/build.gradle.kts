plugins {
    id("listenup.jvm")
    alias(libs.plugins.kotlinSerialization)
}

group = "com.calypsan.listenup"
version = "0.0.1"

dependencies {
    implementation(projects.contract)

    // Ktor server surface used by the embedded web routes (mounted by :server).
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.resources)

    // Loopback REST client (used in Phase 1B; declared now so the module is self-contained).
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // Logging
    implementation(libs.kotlin.logging)

    // Test
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.konsist)
}

kotlin {
    compilerOptions {
        // :contract is compiled by a pre-release Kotlin; allow consuming its classfiles.
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }
}

tasks.test {
    useJUnitPlatform()
}
