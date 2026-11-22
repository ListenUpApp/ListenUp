package com.calypsan.listenup.client.notifications

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [NotificationChannels].
 *
 * Verifies that channel id constants are stable and that [NotificationChannels.allIds]
 * returns them in the expected order. Channel ids are write-once per device — the moment
 * a user's Android registers a channel, the id is locked into their system Settings.
 * A rename here would orphan the existing channel and create a ghost in the user's notification
 * settings.
 */
class NotificationChannelsTest :
    FunSpec({
        test("PLAYBACK channel id is listenup_playback") {
            NotificationChannels.PLAYBACK shouldBe "listenup_playback"
        }

        test("SOCIAL channel id is listenup_social") {
            NotificationChannels.SOCIAL shouldBe "listenup_social"
        }

        test("DOWNLOADS channel id is listenup_downloads, never the retired listenup_download") {
            NotificationChannels.DOWNLOADS shouldBe "listenup_downloads"
            NotificationChannels.RETIRED_CHANNEL_IDS.contains(NotificationChannels.DOWNLOADS) shouldBe false
        }

        test("allIds returns the three live channel ids in order") {
            NotificationChannels.allIds() shouldBe listOf("listenup_playback", "listenup_social", "listenup_downloads")
        }
    })
