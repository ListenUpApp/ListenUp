package com.calypsan.listenup.server.transcode

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TranscodeSettingsTest :
    FunSpec({

        test("defaults match the approved design") {
            val settings = TranscodeSettings()

            settings.enabled shouldBe true
            settings.cacheCapBytes shouldBe 10L * 1024 * 1024 * 1024
            settings.maxConcurrentSessions shouldBe 2
            settings.bitrateKbps shouldBe 64
            settings.targetSegmentSeconds shouldBe 10
        }

        // A zero cap is the documented way to turn transcoding off from the admin surface, and it
        // must disable the feature rather than produce a cache that evicts everything instantly.
        test("a zero cache cap disables transcoding") {
            TranscodeSettings(cacheCapBytes = 0).enabled shouldBe false
        }
    })
