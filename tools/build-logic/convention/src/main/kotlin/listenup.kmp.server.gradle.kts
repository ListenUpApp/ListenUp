import com.calypsan.listenup.gradle.LISTENUP_FREE_COMPILER_ARGS

/*
 * Convention for the native-server KMP module (:server): JDK-21 toolchain + the
 * project-wide compiler args, single-sourced from ListenUpCompilerArgs.kt.
 *
 * Deliberately minimal — targets, cinterops, binaries, and per-target options
 * (e.g. the JVM target's JvmTarget.JVM_21) stay in the module's own build script.
 *
 * NOTE: LISTENUP_MAIN_ONLY_COMPILER_ARGS (the RETURN_VALUE_NOT_USED error
 * escalation) is intentionally NOT applied here — :server has never had it and
 * has no @MustUseReturnValues scopes; adding it is a deliberate follow-up, not
 * part of the single-sourcing.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    // Pin compilation to JDK 21 so a newer local/daemon JDK can't shift validation.
    jvmToolchain(21)

    compilerOptions {
        freeCompilerArgs.addAll(LISTENUP_FREE_COMPILER_ARGS)
        // :contract classfiles carry pre-release Kotlin metadata; without this the
        // server compilations reject them.
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }
}
