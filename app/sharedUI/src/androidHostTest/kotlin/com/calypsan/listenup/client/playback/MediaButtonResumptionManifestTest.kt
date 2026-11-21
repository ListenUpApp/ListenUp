package com.calypsan.listenup.client.playback

import android.content.Intent
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pins the manifest half of Media3 playback resumption. [ListenUpSessionCallback.onPlaybackResumption]
 * answers "what should resume?", but Media3 only offers resumption when it finds a `MEDIA_BUTTON`
 * receiver in the manifest: a headset or car "play" pressed while the process is dead is a broadcast,
 * and with no receiver nothing starts the service and the callback is never asked. Media3 expects
 * exactly one such receiver and throws on more, hence the `single()`.
 */
@RunWith(RobolectricTestRunner::class)
class MediaButtonResumptionManifestTest {
    @Test
    fun `a media button press has exactly one receiver that can start playback`() {
        val application = RuntimeEnvironment.getApplication()
        val mediaButton = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(application.packageName)

        val receivers = application.packageManager.queryBroadcastReceivers(mediaButton, 0)

        receivers.map { it.activityInfo.name } shouldBe listOf("androidx.media3.session.MediaButtonReceiver")
    }
}
