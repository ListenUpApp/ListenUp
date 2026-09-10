package com.calypsan.listenup.server.transcode

import com.calypsan.listenup.server.transcodeSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.config.MapApplicationConfig

/**
 * Pins that the three transcode knobs `application.conf` documents actually reach
 * [TranscodeSettings]. Before this existed the settings were constructed bare — an operator
 * setting `LISTENUP_TRANSCODE_CACHE_BYTES` got silence.
 */
class TranscodeSettingsConfigTest :
    FunSpec({

        test("transcodeSettings reads cacheCapBytes from config") {
            MapApplicationConfig("transcode.cacheCapBytes" to "5").transcodeSettings().cacheCapBytes shouldBe 5L
        }

        test("transcodeSettings reads maxConcurrentSessions from config") {
            MapApplicationConfig("transcode.maxConcurrentSessions" to "7")
                .transcodeSettings()
                .maxConcurrentSessions shouldBe 7
        }

        test("transcodeSettings reads bitrateKbps from config") {
            MapApplicationConfig("transcode.bitrateKbps" to "96").transcodeSettings().bitrateKbps shouldBe 96
        }

        test("transcodeSettings falls back to the TranscodeSettings defaults when unset") {
            val settings = MapApplicationConfig().transcodeSettings()

            settings.cacheCapBytes shouldBe TranscodeSettings.DEFAULT_CACHE_CAP_BYTES
            settings.maxConcurrentSessions shouldBe TranscodeSettings.DEFAULT_MAX_CONCURRENT
            settings.bitrateKbps shouldBe TranscodeSettings.DEFAULT_BITRATE_KBPS
            settings.targetSegmentSeconds shouldBe TranscodeSettings.DEFAULT_SEGMENT_SECONDS
        }

        // A typo in an env var must degrade to the default, never take the server down at boot.
        test("a non-numeric config value falls back to the default rather than crashing the boot") {
            val settings =
                MapApplicationConfig(
                    "transcode.cacheCapBytes" to "ten gigs",
                    "transcode.maxConcurrentSessions" to "two",
                    "transcode.bitrateKbps" to "",
                ).transcodeSettings()

            settings.cacheCapBytes shouldBe TranscodeSettings.DEFAULT_CACHE_CAP_BYTES
            settings.maxConcurrentSessions shouldBe TranscodeSettings.DEFAULT_MAX_CONCURRENT
            settings.bitrateKbps shouldBe TranscodeSettings.DEFAULT_BITRATE_KBPS
        }
    })
