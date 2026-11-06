package com.calypsan.listenup.client.playback

import android.net.Uri
import androidx.media3.common.C
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Streaming holds Wi-Fi awake with the screen off; a playlist of downloaded files needs only the CPU. */
@RunWith(RobolectricTestRunner::class)
class WakeModeTest {
    @Test
    fun `downloaded files keep only the CPU awake`() {
        wakeModeFor(listOf(Uri.parse("file:///data/a.m4b"), Uri.parse("content://x/b"))) shouldBe C.WAKE_MODE_LOCAL
    }

    @Test
    fun `a streamed file keeps Wi-Fi awake too`() {
        wakeModeFor(listOf(Uri.parse("file:///data/a.m4b"), Uri.parse("https://server/audio/1"))) shouldBe
            C.WAKE_MODE_NETWORK
        wakeModeFor(listOf(Uri.parse("HTTP://server/audio/1"))) shouldBe C.WAKE_MODE_NETWORK
    }

    @Test
    fun `an empty playlist needs only the CPU`() {
        wakeModeFor(emptyList()) shouldBe C.WAKE_MODE_LOCAL
    }
}
