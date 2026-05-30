plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.api)
    testImplementation(kotlin("test"))
    testImplementation("io.gitlab.arturbosch.detekt:detekt-test:${libs.versions.detekt.get()}")
}

kotlin {
    jvmToolchain(21)
}
