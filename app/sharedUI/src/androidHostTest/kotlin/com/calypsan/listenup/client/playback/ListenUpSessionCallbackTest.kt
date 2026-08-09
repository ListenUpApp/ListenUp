package com.calypsan.listenup.client.playback

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import com.calypsan.listenup.client.automotive.BrowseTreeProvider
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.voice.VoiceIntentResolver
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the transport-command behaviour of [ListenUpSessionCallback], extracted from
 * `PlaybackService` in #1249 as a pure move with no tests of its own.
 *
 * ## What is actually at risk here
 *
 * Every seek in `onCustomCommand` crosses between two coordinate spaces:
 *
 * - **book-relative** — a position in the whole audiobook, which is what
 *   [PlaybackTransport.bookRelativePositionMs] reports and what
 *   [PlaybackTimeline.resolve] consumes.
 * - **file-relative** — a position within the current media item, which is what
 *   `Player.currentPosition` reports and what the single-argument `seekTo` consumes.
 *
 * Confusing the two is the #1241 bug class: the numbers stay plausible, nothing throws,
 * and playback simply lands somewhere wrong. So these tests deliberately keep the fake's
 * book position and file position far apart — a test where both are 5_000 would pass on
 * exactly the defect it exists to catch.
 *
 * The `timeline == null` branch is the sharpest edge: it is the *only* path that legitimately
 * seeks file-relatively, so it is asserted through [FakeExoPlayer.fileRelativeSeekCalls]
 * rather than [FakeExoPlayer.seekCalls], and each case checks that the *other* list stayed
 * empty.
 */
@RunWith(RobolectricTestRunner::class)
class ListenUpSessionCallbackTest {
    // ── Publishing the landing position ───────────────────────────────────────
    //
    // Seeking the player is only half the job: the in-app position comes from a poll that runs
    // *only while playing*. A notification skip taken while paused therefore moved the player
    // and left `PlaybackManager` on the old position — so the next in-app skip computed from
    // the stale value and undid the notification's skip.

    @Test
    fun `skip forward 30 publishes the landing position`() {
        val player = FakeExoPlayer()
        val published = mutableListOf<Pair<Int, Long>>()
        val callback =
            callbackWith(
                player = player,
                bookPositionMs = 120_000L,
                timeline = threeFileTimeline(),
                publishedPositions = published,
            )

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_FORWARD_30)

        // 120_000 + 30_000 = 150_000 book-relative → 30_000 into the third 60_000 ms file.
        // The published pair must match the seek exactly, or the in-app position disagrees
        // with where audio actually is.
        published shouldBe listOf(2 to 30_000L)
    }

    @Test
    fun `skip back 30 publishes the landing position`() {
        val player = FakeExoPlayer()
        val published = mutableListOf<Pair<Int, Long>>()
        val callback =
            callbackWith(
                player = player,
                bookPositionMs = 120_000L,
                timeline = threeFileTimeline(),
                publishedPositions = published,
            )

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_BACK_30)

        published shouldBe listOf(1 to 30_000L)
    }

    @Test
    fun `next chapter publishes the landing position`() {
        val player = FakeExoPlayer()
        val published = mutableListOf<Pair<Int, Long>>()
        val callback =
            callbackWith(
                player = player,
                bookPositionMs = 10_000L,
                timeline = threeFileTimeline(),
                chapters = twoChapters(),
                publishedPositions = published,
            )

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_NEXT_CHAPTER)

        // Second chapter starts 90_000 into the book → 30_000 into the second file.
        published shouldBe listOf(1 to 30_000L)
    }

    @Test
    fun `a seek without a timeline still publishes the file-relative landing position`() {
        val player = FakeExoPlayer(stubbedPosition = 45_000L, stubbedMediaItemIndex = 1)
        val published = mutableListOf<Pair<Int, Long>>()
        val callback =
            callbackWith(
                player = player,
                bookPositionMs = 105_000L,
                timeline = null,
                publishedPositions = published,
            )

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_BACK_30)

        // The only branch that legitimately seeks file-relatively: 45_000 − 30_000 within the
        // current item, so the published index must be the item the player is already on.
        player.fileRelativeSeekCalls shouldBe listOf(15_000L)
        published shouldBe listOf(1 to 15_000L)
    }

    // ── Skip back ─────────────────────────────────────────────────────────────

    @Test
    fun `skip back 30 resolves the book-relative target through the timeline`() {
        val player = FakeExoPlayer()
        val callback = callbackWith(player = player, bookPositionMs = 120_000L, timeline = threeFileTimeline())

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_BACK_30)

        // 120_000 − 30_000 = 90_000 book-relative, which lands 30_000 into the second
        // 60_000 ms file. Both halves of the pair matter: an index-only or offset-only
        // regression is still a wrong seek.
        player.seekCalls shouldBe listOf(1 to 30_000L)
        player.fileRelativeSeekCalls.shouldBeEmpty()
    }

    @Test
    fun `skip back 30 clamps at the start of the book`() {
        val player = FakeExoPlayer()
        val callback = callbackWith(player = player, bookPositionMs = 10_000L, timeline = threeFileTimeline())

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_BACK_30)

        // 10_000 − 30_000 would be −20_000. Clamped to 0, which resolves to the very
        // start of the first file rather than a negative offset ExoPlayer would reject.
        player.seekCalls shouldBe listOf(0 to 0L)
    }

    // ── Skip forward ──────────────────────────────────────────────────────────

    @Test
    fun `skip forward 30 clamps at the total duration of the book`() {
        val player = FakeExoPlayer()
        val callback = callbackWith(player = player, bookPositionMs = 170_000L, timeline = threeFileTimeline())

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_FORWARD_30)

        // 170_000 + 30_000 = 200_000, past the 180_000 ms end. Clamped to totalDurationMs,
        // which resolve() maps to the end of the final file — never a fourth index.
        //
        // Scope note: this pins the observable outcome, not the clamp itself. resolve()
        // independently maps any past-the-end position to the last file, so deleting
        // `coerceAtMost(maxPosition)` would leave this green (verified by mutation). It does
        // catch a *wrong* clamp — halving the bound fails here — which is the likelier defect.
        player.seekCalls shouldBe listOf(2 to 60_000L)
        player.fileRelativeSeekCalls.shouldBeEmpty()
    }

    // ── The timeline == null fallback ─────────────────────────────────────────

    @Test
    fun `skip back 30 without a timeline seeks FILE-relatively, not book-relatively`() {
        // The fake reports a file position of 40_000 while sitting at 160_000 in the book.
        // With no timeline there is nothing to translate between them, so the only correct
        // answer is the file-relative one.
        val player = FakeExoPlayer(stubbedPosition = 40_000L)
        val callback = callbackWith(player = player, bookPositionMs = 160_000L, timeline = null)

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_BACK_30)

        // 40_000 − 30_000 = 10_000, file-relative. Had the fallback reached for the book
        // position instead, this would be 130_000 — a seek far past the end of the file.
        player.fileRelativeSeekCalls shouldBe listOf(10_000L)
        player.seekCalls.shouldBeEmpty()
    }

    @Test
    fun `skip forward 30 without a timeline clamps to the file duration`() {
        val player = FakeExoPlayer(stubbedPosition = 40_000L, stubbedDuration = 50_000L)
        val callback = callbackWith(player = player, bookPositionMs = 160_000L, timeline = null)

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_FORWARD_30)

        // 40_000 + 30_000 = 70_000, past the file's 50_000 ms end, so it clamps to the
        // file duration — the file's end, not the book's.
        player.fileRelativeSeekCalls shouldBe listOf(50_000L)
        player.seekCalls.shouldBeEmpty()
    }

    // ── No transport player ───────────────────────────────────────────────────

    @Test
    fun `a custom command with no transport player errors instead of seeking`() {
        val player = FakeExoPlayer()
        // activeTransportPlayer() is null between a cast handoff and the receiver being
        // ready — the callback must refuse rather than fall back to some other player.
        val callback = callbackWith(player = null, bookPositionMs = 120_000L, timeline = threeFileTimeline())

        val result = callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_BACK_30)

        result.resultCode shouldBe SessionResult.RESULT_ERROR_UNKNOWN
        player.seekCalls.shouldBeEmpty()
        player.fileRelativeSeekCalls.shouldBeEmpty()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Blocking, because `onCustomCommand` returns an already-completed immediate future.
     *
     * `onCustomCommand` reads neither `session` nor `controller`, but Kotlin emits a
     * non-null intrinsic on both, so real instances are required: Media3 ships
     * [MediaSession.ControllerInfo.createTestOnlyControllerInfo] for exactly this, and the
     * session is built over [sessionPlayer] and released before returning.
     */
    private fun ListenUpSessionCallback.invokeCustomCommand(action: String): SessionResult {
        val session = MediaSession.Builder(context, sessionPlayer).build()
        try {
            return onCustomCommand(
                session,
                MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                    "com.calypsan.listenup.test",
                    // pid =
                    0,
                    // uid =
                    0,
                    // libraryVersion =
                    0,
                    // interfaceVersion =
                    0,
                    // trusted =
                    true,
                    Bundle.EMPTY,
                    // isPackageNameVerified =
                    true,
                ),
                SessionCommand(action, Bundle.EMPTY),
                Bundle.EMPTY,
            ).get()
        } finally {
            session.release()
        }
    }

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * The player the [MediaSession] is built over — deliberately a separate instance from the
     * one under assertion, so a seek accidentally routed to the session player instead of the
     * transport player shows up as a missing call rather than passing unnoticed.
     */
    private val sessionPlayer = FakeExoPlayer(canAdvertiseSession = true)

    /**
     * Three 60_000 ms files, so the book runs 0..180_000 ms and every file boundary is a
     * round number — a wrong index or a wrong offset reads as an obvious wrong number
     * rather than an arithmetic coincidence.
     */
    private fun threeFileTimeline(): PlaybackTimeline =
        PlaybackTimeline(
            bookId = BookId("book1"),
            totalDurationMs = 180_000L,
            files =
                (0..2).map { index ->
                    PlaybackTimeline.FileSegment(
                        audioFileId = "af-$index",
                        filename = "file$index.mp3",
                        format = "mp3",
                        startOffsetMs = index * 60_000L,
                        durationMs = 60_000L,
                        size = 1_000L,
                        streamingUrl = "https://example.invalid/file$index.mp3",
                        localPath = null,
                        mediaItemIndex = index,
                    )
                },
        )

    /** Two chapters over the 180 s book: 0–90 s and 90–180 s. */
    private fun twoChapters(): List<Chapter> =
        listOf(
            Chapter(id = "c1", title = "One", duration = 90_000L, startTime = 0L),
            Chapter(id = "c2", title = "Two", duration = 90_000L, startTime = 90_000L),
        )

    /**
     * Builds the callback with only the two dependencies `onCustomCommand` touches wired for
     * real — the transport seam and [PlaybackManager]. The other seven are browse, search,
     * voice and persistence collaborators that this path never reaches: those mokkery can
     * mock are mocked with no stubbed answers, and the two final classes it cannot are
     * [unreachable]. Either way a call fails loudly rather than quietly returning a default.
     */
    private fun callbackWith(
        player: Player?,
        bookPositionMs: Long,
        timeline: PlaybackTimeline?,
        chapters: List<Chapter> = emptyList(),
        publishedPositions: MutableList<Pair<Int, Long>> = mutableListOf(),
    ): ListenUpSessionCallback {
        val playbackManager = mock<PlaybackManager>()
        every { playbackManager.chapters } returns MutableStateFlow(chapters)
        every { playbackManager.currentTimeline } returns MutableStateFlow(timeline)
        every { playbackManager.updatePositionFromMediaItem(any(), any()) } calls
            { (index: Int, positionMs: Long) -> publishedPositions += index to positionMs }

        return ListenUpSessionCallback(
            context = context,
            playbackManager = playbackManager,
            browseTreeProvider = unreachedBrowseTreeProvider(),
            voiceIntentResolver = unreachedVoiceIntentResolver(),
            homeRepository = mock<HomeRepository>(),
            authSession = mock<AuthSession>(),
            positionRepository = mock<PlaybackPositionRepository>(),
            serviceScope = CoroutineScope(Dispatchers.Unconfined),
            transport = FakePlaybackTransport(player, bookPositionMs),
        )
    }

    /**
     * `BrowseTreeProvider` and `VoiceIntentResolver` are final classes mokkery cannot mock,
     * and Kotlin's non-null intrinsic rules out a placeholder — but both take nothing except
     * repository interfaces, so a real instance over unstubbed mocks is cheap. Neither is
     * reached by `onCustomCommand`; if that ever changes, the unstubbed mocks underneath fail
     * loudly rather than quietly answering.
     */
    private fun unreachedBrowseTreeProvider(): BrowseTreeProvider =
        BrowseTreeProvider(
            homeRepository = mock<HomeRepository>(),
            bookRepository = mock<BookRepository>(),
            seriesRepository = mock<SeriesRepository>(),
            contributorRepository = mock<ContributorRepository>(),
            downloadRepository = mock<DownloadRepository>(),
            packageName = "com.calypsan.listenup.client",
        )

    private fun unreachedVoiceIntentResolver(): VoiceIntentResolver =
        VoiceIntentResolver(
            searchRepository = mock<SearchRepository>(),
            homeRepository = mock<HomeRepository>(),
            seriesRepository = mock<SeriesRepository>(),
            bookRepository = mock<BookRepository>(),
        )
}

/** Records nothing: these tests assert on the player the transport hands out, not on the seam itself. */
private class FakePlaybackTransport(
    private val player: Player?,
    private val bookPositionMs: Long,
) : PlaybackTransport {
    override fun activeTransportPlayer(): Player? = player

    override fun bookRelativePositionMs(): Long = bookPositionMs

    override fun applyResumeSpeed(speed: Float) = Unit
}
