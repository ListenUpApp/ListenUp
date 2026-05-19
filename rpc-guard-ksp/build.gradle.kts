plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.ksp.symbol.processing.api)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kctfork.ksp)
    // Required so the source stubs compiled inside kctfork tests can resolve @Rpc,
    // AppResult<T>, and RpcEvent<T> — these are the types the processor validates.
    testImplementation(libs.kotlinx.rpc.core)
    testImplementation(project(":sharedLogic"))
}

tasks.test {
    useJUnitPlatform()
}
