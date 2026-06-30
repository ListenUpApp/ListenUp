package com.calypsan.listenup.server.io

import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.setenv
import kotlin.test.Test

/**
 * Native proof for the [readEnv] / [userHomeDir] / [hostname] env seam: the
 * linuxX64 actuals read the process environment via `platform.posix.getenv` and the host name via
 * `gethostname`. Round-trips a value set with `setenv`, confirms an unset name reads as null, and proves
 * the host-name actual returns a sane, NUL-stripped string.
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

    @Test
    fun hostnameReturnsANonBlankHostName() {
        // The value is host-dependent (the CI container's name), but `gethostname` always succeeds on a
        // real host, and the actual must strip the trailing NUL padding from the fixed-size buffer.
        val name = hostname()
        name.isNotBlank() shouldBe true
        name.all { it.code in 33..126 } shouldBe true
    }
}
