package com.calypsan.listenup.client.playback.cast

import com.google.android.gms.cast.CastMediaControlIntent
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the two Cast settings that Media3 1.11 would otherwise decide for us.
 *
 * `CastParams` leaves every field null by default and only writes the ones that were set, so an
 * unset field inherits whatever Media3 currently defaults to. Both defaults would move us:
 *
 * - **Receiver application id** — unset falls through to `DefaultCastOptionsProvider`, which is the
 *   Default Media Receiver *with DRM* (`A12D4273`), not the plain one we have always cast to.
 * - **System output switcher** — on a device running API 37+, an app targeting SDK 37+ (that is us)
 *   defaults to `true`, so tapping the cast button would open the system output switcher instead of
 *   the Cast device dialog. That is a product decision, not a dependency-bump side effect.
 *
 * Same lesson as [com.calypsan.listenup.client.playback.PlayerCommandPolicyTest]: state the intent
 * explicitly so the next default change cannot quietly become our behaviour.
 */
@RunWith(RobolectricTestRunner::class)
class CastParamsPolicyTest {
    @Test
    fun `casts to the plain default media receiver, not the DRM one`() {
        listenUpCastParams().receiverApplicationId shouldBe
            CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
    }

    @Test
    fun `keeps the cast button opening the Cast dialog, not the system output switcher`() {
        listenUpCastParams().showSystemOutputSwitcherOnCastButtonClick shouldBe false
    }
}
