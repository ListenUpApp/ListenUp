package com.calypsan.listenup.client.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import io.kotest.matchers.shouldBe

/**
 * Tests for the pure formatting helpers in [AudiobookNotificationProvider]:
 * [AudiobookNotificationProvider.buildChapterSubtitle] and
 * [AudiobookNotificationProvider.formatDuration].
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
 * Scope: ONLY [buildChapterSubtitle] and [formatDuration]. The full
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
                playbackManager = StubPlaybackManager(),
            )
    }

    // ── buildChapterSubtitle — null chapter ───────────────────────────────────

    @Test
    fun `buildChapterSubtitle returns Playing dot dot dot when chapter is null`() {
        val result = provider.buildChapterSubtitle(null)
        result shouldBe "Playing..."
    }

    // ── buildChapterSubtitle — generic chapter (isGenericTitle = true) ────────

    @Test
    fun `buildChapterSubtitle formats generic chapter with index plus one of total`() {
        val chapter = makeChapterInfo(index = 0, totalChapters = 92, isGenericTitle = true, remainingMs = 8 * 60_000L)
        val result = provider.buildChapterSubtitle(chapter)
        // index 0 → "Chapter 1 of 92", 8 minutes → "8m"
        result shouldBe "Chapter 1 of 92 • 8m left"
    }

    @Test
    fun `buildChapterSubtitle formats generic chapter index 13 of 92`() {
        val chapter = makeChapterInfo(index = 13, totalChapters = 92, isGenericTitle = true, remainingMs = 8 * 60_000L)
        val result = provider.buildChapterSubtitle(chapter)
        result shouldBe "Chapter 14 of 92 • 8m left"
    }

    @Test
    fun `buildChapterSubtitle formats generic chapter when only one chapter exists`() {
        val chapter = makeChapterInfo(index = 0, totalChapters = 1, isGenericTitle = true, remainingMs = 30 * 60_000L)
        val result = provider.buildChapterSubtitle(chapter)
        result shouldBe "Chapter 1 of 1 • 30m left"
    }

    // ── buildChapterSubtitle — named chapter (isGenericTitle = false) ─────────

    @Test
    fun `buildChapterSubtitle uses chapter title directly for named chapters`() {
        val chapter =
            makeChapterInfo(
                index = 13,
                totalChapters = 92,
                isGenericTitle = false,
                title = "Chapter 14: The Chandrian",
                remainingMs = 8 * 60_000L,
            )
        val result = provider.buildChapterSubtitle(chapter)
        result shouldBe "Chapter 14: The Chandrian • 8m left"
    }

    @Test
    fun `buildChapterSubtitle uses chapter title for named chapter with hour remaining`() {
        val chapter =
            makeChapterInfo(
                index = 0,
                totalChapters = 5,
                isGenericTitle = false,
                title = "Prologue",
                remainingMs = 75 * 60_000L, // 1h 15m
            )
        val result = provider.buildChapterSubtitle(chapter)
        result shouldBe "Prologue • 1h 15m left"
    }

    // ── buildChapterSubtitle — edge remaining times ───────────────────────────

    @Test
    fun `buildChapterSubtitle shows less than 1m for zero remaining`() {
        val chapter = makeChapterInfo(index = 0, totalChapters = 10, isGenericTitle = true, remainingMs = 0L)
        val result = provider.buildChapterSubtitle(chapter)
        result shouldBe "Chapter 1 of 10 • < 1m left"
    }

    @Test
    fun `buildChapterSubtitle shows less than 1m for 59 seconds remaining`() {
        val chapter = makeChapterInfo(index = 0, totalChapters = 10, isGenericTitle = true, remainingMs = 59_999L)
        val result = provider.buildChapterSubtitle(chapter)
        result shouldBe "Chapter 1 of 10 • < 1m left"
    }

    // ── notificationTitle — book title, not chapter title ────────────────────
    //
    // The session player is ChapterWindowPlayer, which deliberately overrides the item title
    // with the *chapter* title so system surfaces present a chapter the way a music player
    // presents a track. The phone notification reading that same metadata is what silently
    // replaced the book title with the chapter title — and the chapter is already the subtitle,
    // so the book's name disappeared from the notification entirely.

    @Test
    fun `notificationTitle prefers the book title over the session player's chapter title`() {
        provider.notificationTitle(
            bookTitle = "The Way of Kings",
            sessionTitle = "Chapter 14: The Shattered Plains",
        ) shouldBe "The Way of Kings"
    }

    @Test
    fun `notificationTitle falls back to the session title when no book title is available`() {
        // Never stranded: a chapter title in the notification beats "Unknown Book".
        provider.notificationTitle(
            bookTitle = null,
            sessionTitle = "Chapter 14: The Shattered Plains",
        ) shouldBe "Chapter 14: The Shattered Plains"
    }

    @Test
    fun `notificationTitle falls back to Unknown Book when nothing is known`() {
        provider.notificationTitle(bookTitle = null, sessionTitle = null) shouldBe "Unknown Book"
    }

    @Test
    fun `notificationTitle ignores a blank book title`() {
        provider.notificationTitle(bookTitle = "  ", sessionTitle = "Chapter 3") shouldBe "Chapter 3"
    }

    // ── formatDuration — boundary cases ──────────────────────────────────────

    @Test
    fun `formatDuration returns less than 1m for 0ms`() {
        provider.formatDuration(0L) shouldBe "< 1m"
    }

    @Test
    fun `formatDuration returns less than 1m for 59999ms (just under 1 minute)`() {
        provider.formatDuration(59_999L) shouldBe "< 1m"
    }

    @Test
    fun `formatDuration returns 1m for exactly 60000ms`() {
        provider.formatDuration(60_000L) shouldBe "1m"
    }

    @Test
    fun `formatDuration returns 8m for 8 minutes`() {
        provider.formatDuration(8 * 60_000L) shouldBe "8m"
    }

    @Test
    fun `formatDuration returns 59m for 59 minutes`() {
        provider.formatDuration(59 * 60_000L) shouldBe "59m"
    }

    @Test
    fun `formatDuration returns 59m for 3599999ms (just under 1 hour)`() {
        provider.formatDuration(3_599_999L) shouldBe "59m"
    }

    @Test
    fun `formatDuration returns 1h 0m for exactly 1 hour (3600000ms)`() {
        provider.formatDuration(3_600_000L) shouldBe "1h 0m"
    }

    @Test
    fun `formatDuration returns 1h 15m for 75 minutes`() {
        provider.formatDuration(75 * 60_000L) shouldBe "1h 15m"
    }

    @Test
    fun `formatDuration returns 2h 0m for exactly 2 hours`() {
        provider.formatDuration(2 * 3_600_000L) shouldBe "2h 0m"
    }

    @Test
    fun `formatDuration returns 10h 30m for large values`() {
        provider.formatDuration((10 * 60 + 30) * 60_000L) shouldBe "10h 30m"
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun makeChapterInfo(
    index: Int,
    totalChapters: Int,
    isGenericTitle: Boolean,
    title: String = "Chapter ${index + 1}",
    remainingMs: Long = 0L,
): PlaybackManager.ChapterInfo =
    PlaybackManager.ChapterInfo(
        index = index,
        title = title,
        startMs = 0L,
        endMs = remainingMs,
        remainingMs = remainingMs,
        totalChapters = totalChapters,
        isGenericTitle = isGenericTitle,
    )

/**
 * Minimal [PlaybackManager] stub for constructing [AudiobookNotificationProvider] in tests.
 *
 * [AudiobookNotificationProvider] reads [PlaybackManager.currentChapter] in
 * [AudiobookNotificationProvider.createNotification], which is NOT exercised by the formatter
 * tests. All other methods throw [UnsupportedOperationException] if called.
 */
private class StubPlaybackManager : PlaybackManager {
    override val currentChapter: StateFlow<PlaybackManager.ChapterInfo?> = MutableStateFlow(null)
    override var onChapterChanged: ((PlaybackManager.ChapterInfo) -> Unit)? = null
    override val currentBookId: StateFlow<BookId?> = MutableStateFlow(null)
    override val currentTimeline: StateFlow<PlaybackTimeline?> = MutableStateFlow(null)
    override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
    override val isBuffering: StateFlow<Boolean> = MutableStateFlow(false)
    override val currentPositionMs: StateFlow<Long> = MutableStateFlow(0L)
    override val totalDurationMs: StateFlow<Long> = MutableStateFlow(0L)
    override val playbackSpeed: StateFlow<Float> = MutableStateFlow(1.0f)
    override val volumeBoostDb: StateFlow<Float> = MutableStateFlow(0f)
    override val playbackState: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Idle)
    override val playbackError: StateFlow<PlaybackManager.PlaybackErrorUiState?> = MutableStateFlow(null)
    override val chapters: StateFlow<List<Chapter>> = MutableStateFlow(emptyList())
    override val effectiveGainDb: StateFlow<Float> = MutableStateFlow(0f)

    override fun activateBook(bookId: BookId) = Unit

    override suspend fun prepareForPlayback(bookId: BookId): PlaybackManager.PrepareResult? = null

    override suspend fun startPlayback(
        player: AudioPlayer,
        resumePositionMs: Long,
        resumeSpeed: Float,
    ) = Unit

    override fun onSpeedChanged(speed: Float) = Unit

    override fun onSpeedReset(defaultSpeed: Float) = Unit

    override fun onVolumeBoostChanged(boostDb: Float) = Unit

    override fun onBoostReset(defaultBoostDb: Float) = Unit

    override fun clearPlayback() = Unit

    override fun clearError() = Unit

    override fun setPlaying(playing: Boolean) = Unit

    override fun setBuffering(buffering: Boolean) = Unit

    override fun setPlaybackState(state: PlaybackState) = Unit

    override fun updatePosition(positionMs: Long) = Unit

    override fun updatePositionFromMediaItem(
        mediaItemIndex: Int,
        positionInItemMs: Long,
    ) = Unit

    override fun updateSpeed(speed: Float) = Unit

    override fun reportError(
        message: String,
        isRecoverable: Boolean,
    ) = Unit
}
