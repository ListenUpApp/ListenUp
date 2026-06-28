package com.calypsan.listenup.server.io

import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.setenv
import kotlin.test.Test

/**
 * Native proof for the [readEnv] / [userHomeDir] env seam (Phase 5 capability port): the linuxX64
 * actuals read the process environment via `platform.posix.getenv`. Round-trips a value set with
 * `setenv` and confirms an unset name reads as null — the contract `resolveServerSecrets` /
 * `resolveListenupHome` rely on when reading `LISTENUP_HOME` / `LISTENUP_JWT_SECRET` natively.
 */
class SystemEnvNativeTest {
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun readEnvRoundTripsSetenvAndReturnsNullForUnset() {
        setenv("LISTENUP_NATIVE_ENV_TEST", "native-value-42", 1)
        readEnv("LISTENUP_NATIVE_ENV_TEST") shouldBe "native-value-42"
        readEnv("LISTENUP_NATIVE_ENV_DEFINITELY_UNSET") shouldBe null
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun userHomeDirReadsHomeEnv() {
        setenv("HOME", "/home/listenup-native-test", 1)
        userHomeDir() shouldBe "/home/listenup-native-test"
    }
}
