package com.calypsan.listenup.client.localization

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the catalog seam the platform surfaces read through (#1246).
 *
 * The failure this guards against is silent by nature: these strings are handed to Media3, Android
 * Auto and the notification manager, so a wrong one does not throw and does not log — it ships as a
 * mislabelled button in a car, where nobody testing on a phone will ever see it.
 *
 * The load-equals-fallback assertion is the one that carries the weight. [SystemStrings] keeps a
 * hand-written English snapshot for the milliseconds before the first catalog load lands, and a
 * duplicate is only honest while it agrees with the original — so this fails the moment `en.json`
 * copy moves without the fallback moving with it.
 *
 * Robolectric, because `getString` resolves against a real `ResourceEnvironment`.
 */
@RunWith(RobolectricTestRunner::class)
class SystemStringsTest {
    @Test
    fun `the pre-load fallback says exactly what the catalog resolves`() =
        runTest {
            loadSystemStrings() shouldBe SystemStrings.ENGLISH_FALLBACK
        }

    @Test
    fun `format strings keep their placeholders for the call site to fill`() =
        runTest {
            // The mirror image: these are formatted at the point of use, so they must arrive with
            // placeholders intact. Losing one silently drops the chapter number or the author name.
            val loaded = loadSystemStrings()

            loaded.playerChapterOf shouldBe "Chapter %1\$s of %2\$s"
            loaded.playerChapterRemaining shouldBe "%1\$s • %2\$s left"
            loaded.carBookSubtitle shouldBe "%1\$s - %2\$s"

            // The skip labels joined this group in #1300: the seconds are the user's synced
            // setting, read at render time, so the loader must NOT bake a number in here.
            loaded.playerSkipBackward shouldBe "Skip backward %1\$s seconds"
            loaded.playerSkipForward shouldBe "Skip forward %1\$s seconds"
            loaded.carBack shouldBe "Back %1\$ss"
            loaded.carForward shouldBe "Forward %1\$ss"
        }

    @Test
    fun `the holder serves the fallback until a refresh lands`() =
        runTest {
            val holder = SystemStringsHolder()

            holder.current shouldBe SystemStrings.ENGLISH_FALLBACK
            holder.refresh()
            holder.current shouldBe loadSystemStrings()
        }
}
