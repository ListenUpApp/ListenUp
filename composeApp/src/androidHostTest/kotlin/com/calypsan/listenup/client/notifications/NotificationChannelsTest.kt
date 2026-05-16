package com.calypsan.listenup.client.notifications

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [NotificationChannels].
 *
 * Verifies that channel id constants are stable and that [NotificationChannels.allIds]
 * returns them in the expected order. Channel ids are write-once per device — the moment
 * a user's Android registers a channel, the id is locked into their system Settings.
 * A rename here would orphan the existing channel and create a ghost in the user's notification
 * settings.
 */
class NotificationChannelsTest {
    @Test
    fun `PLAYBACK channel id is listenup_playback`() {
        assertEquals("listenup_playback", NotificationChannels.PLAYBACK)
    }

    @Test
    fun `SYNC channel id is listenup_sync`() {
        assertEquals("listenup_sync", NotificationChannels.SYNC)
    }

    @Test
    fun `DOWNLOAD channel id is listenup_download`() {
        assertEquals("listenup_download", NotificationChannels.DOWNLOAD)
    }

    @Test
    fun `allIds returns all three channel ids in order`() {
        assertEquals(
            listOf("listenup_playback", "listenup_sync", "listenup_download"),
            NotificationChannels.allIds(),
        )
    }
}
