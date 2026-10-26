package com.calypsan.listenup.client.playback

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import dev.mokkery.mock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Tests for the pure helpers in [AudiobookNotificationProvider]: the notification's words, read from
 * the session's metadata, and the artwork downsampling. The time-left wording moved to the pure
 * `systemTrackLine` (ChapterWindowTest), because the session, not the notification, now carries it.
 *
 * These methods are `internal` (widened from `private` for testability) and can be called
 * directly on a provider instance. [AudiobookNotificationProvider] requires [android.content.Context]
 * in its constructor (to look up drawable resource IDs), so Robolectric is used here to supply
 * a real Android context that satisfies `context.resources.getIdentifier(...)`.
 *
 * JUnit4 + [RobolectricTestRunner] is used (consistent with [DeepLinkParserTest]);
 * the `junit-vintage-engine` on the classpath keeps these tests discoverable on the JUnit5
 * platform alongside Kotest specs in `androidHostTest`.
 *
 * Scope: ONLY the words and the artwork decode. The full
 * [AudiobookNotificationProvider.createNotification] path is out of scope — it requires
 * a live [androidx.media3.session.MediaSession] and [Player] and is not cleanly testable
 * in a unit test.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(UnstableApi::class)
class AudiobookNotificationProviderTest {
    private lateinit var provider: AudiobookNotificationProvider

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        provider =
            AudiobookNotificationProvider(
                context = context,
                // These tests only exercise the pure subtitle/duration helpers, so the holder
                // never leaves its stock intervals — nothing here calls `follow`.
                skipIntervals = SkipIntervalsHolder(mock<PlaybackPreferences>()),
            )
    }

    // ── The notification's words are the session's ────────────────────────────
    //
    // Android 13+ draws the media card from the platform session, not from this notification, so the
    // session metadata (ChapterWindowPlayer: title = the book, artist = the chapter and its time left)
    // is the one source; the notification text reads it so no surface can say something else.

    @Test
    fun `the notification's title and text are the session's title and artist`() {
        val metadata =
            MediaMetadata
                .Builder()
                .setTitle("The Way of Kings")
                .setArtist("Bridge Four · 8m left")
                .build()

        provider.notificationTitle(metadata) shouldBe "The Way of Kings"
        provider.notificationText(metadata) shouldBe "Bridge Four · 8m left"
    }

    @Test
    fun `with no session metadata the title is Unknown book`() {
        provider.notificationTitle(MediaMetadata.EMPTY) shouldBe "Unknown book"
        provider.notificationTitle(MediaMetadata.Builder().setTitle("  ").build()) shouldBe "Unknown book"
    }

    // ── decodeDownsampled — the artwork the cache actually holds ──────────────

    @Test
    fun `decodeDownsampled brings a large cover under the notification cap`() {
        // Over twice the cap, so the decode has to halve more than once; a full 2400 px cover (23 MB, plus its
        // encoded copy) ran this lane's shared 512 MB test heap out of memory on CI.
        val cover = Bitmap.createBitmap(1100, 1100, Bitmap.Config.ARGB_8888)
        val encoded =
            ByteArrayOutputStream().also { cover.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

        val decoded = decodeDownsampled(openStream = { ByteArrayInputStream(encoded) })

        decoded!!.width shouldBeLessThanOrEqual NOTIFICATION_ARTWORK_MAX_EDGE_PX
    }
}
