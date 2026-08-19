package com.calypsan.listenup.client.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.automotive.BrowseTree
import com.calypsan.listenup.client.automotive.BrowseTreeProvider
import com.calypsan.listenup.client.automotive.CoverUri
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.localization.SystemStringsHolder
import com.calypsan.listenup.client.voice.VoiceIntentResolver
import com.calypsan.listenup.core.BookId
import com.google.common.util.concurrent.ListenableFuture
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
    fun `skip forward publishes the landing position`() {
        val player = FakeExoPlayer()
        val published = mutableListOf<Pair<Int, Long>>()
        val callback =
            callbackWith(
                player = player,
                bookPositionMs = 120_000L,
                timeline = threeFileTimeline(),
                publishedPositions = published,
            )

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_FORWARD)

        // 120_000 + 30_000 = 150_000 book-relative → 30_000 into the third 60_000 ms file.
        // The published pair must match the seek exactly, or the in-app position disagrees
        // with where audio actually is.
        published shouldBe listOf(2 to 30_000L)
    }

    @Test
    fun `skip back publishes the landing position`() {
        val player = FakeExoPlayer()
        val published = mutableListOf<Pair<Int, Long>>()
        val callback =
            callbackWith(
                player = player,
                bookPositionMs = 120_000L,
                timeline = threeFileTimeline(),
                publishedPositions = published,
            )

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_BACK)

        // 120_000 − 10_000 (the stock back interval) = 110_000 book-relative → 50_000 into
        // the second 60_000 ms file.
        published shouldBe listOf(1 to 50_000L)
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

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_BACK)

        // The only branch that legitimately seeks file-relatively: 45_000 − 10_000 within the
        // current item, so the published index must be the item the player is already on.
        player.fileRelativeSeekCalls shouldBe listOf(35_000L)
        published shouldBe listOf(1 to 35_000L)
    }

    // ── Skip back ─────────────────────────────────────────────────────────────

    @Test
    fun `skip back resolves the book-relative target through the timeline`() {
        val player = FakeExoPlayer()
        val callback = callbackWith(player = player, bookPositionMs = 120_000L, timeline = threeFileTimeline())

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_BACK)

        // 120_000 − 10_000 = 110_000 book-relative, which lands 50_000 into the second
        // 60_000 ms file. Both halves of the pair matter: an index-only or offset-only
        // regression is still a wrong seek.
        player.seekCalls shouldBe listOf(1 to 50_000L)
        player.fileRelativeSeekCalls.shouldBeEmpty()
    }

    @Test
    fun `skip back clamps at the start of the book`() {
        val player = FakeExoPlayer()
        val callback = callbackWith(player = player, bookPositionMs = 5_000L, timeline = threeFileTimeline())

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_BACK)

        // 5_000 − 10_000 would be −5_000. Clamped to 0, which resolves to the very
        // start of the first file rather than a negative offset ExoPlayer would reject.
        player.seekCalls shouldBe listOf(0 to 0L)
    }

    // ── Skip forward ──────────────────────────────────────────────────────────

    @Test
    fun `skip forward clamps at the total duration of the book`() {
        val player = FakeExoPlayer()
        val callback = callbackWith(player = player, bookPositionMs = 170_000L, timeline = threeFileTimeline())

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_FORWARD)

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

    // ── The configured skip intervals (#1300) ─────────────────────────────────
    //
    // These commands are the notification's and Android Auto's skip buttons. They were nailed to
    // 30 s in BOTH directions, so a listener who set 45/20 in Settings got 30/30 from every
    // system surface — and the phone notification disagreed with the in-app buttons on the same
    // screen. 45 and 20 are used below precisely because neither is a stock default: a test
    // written around 30/10 would pass on exactly the defect it exists to catch.

    @Test
    fun `skip forward moves by the configured interval, not a hardcoded 30 seconds`() {
        val player = FakeExoPlayer()
        val callback =
            callbackWith(
                player = player,
                bookPositionMs = 120_000L,
                timeline = threeFileTimeline(),
                skipIntervals = intervals(forwardSec = 45, backwardSec = 20),
            )

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_FORWARD)

        // 120_000 + 45_000 = 165_000 book-relative → 45_000 into the third 60_000 ms file.
        player.seekCalls shouldBe listOf(2 to 45_000L)
    }

    @Test
    fun `skip back moves by the configured interval, not a hardcoded 30 seconds`() {
        val player = FakeExoPlayer()
        val callback =
            callbackWith(
                player = player,
                bookPositionMs = 120_000L,
                timeline = threeFileTimeline(),
                skipIntervals = intervals(forwardSec = 45, backwardSec = 20),
            )

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_BACK)

        // 120_000 − 20_000 = 100_000 book-relative → 40_000 into the second file. The old code
        // subtracted 30_000 here, which is neither the configured value nor the in-app one.
        player.seekCalls shouldBe listOf(1 to 40_000L)
    }

    @Test
    fun `a skip taken with no timeline also uses the configured interval`() {
        val player = FakeExoPlayer(stubbedPosition = 40_000L, stubbedDuration = 200_000L)
        val callback =
            callbackWith(
                player = player,
                bookPositionMs = 160_000L,
                timeline = null,
                skipIntervals = intervals(forwardSec = 45, backwardSec = 20),
            )

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_FORWARD)

        player.fileRelativeSeekCalls shouldBe listOf(85_000L)
    }

    // ── The timeline == null fallback ─────────────────────────────────────────

    @Test
    fun `skip back without a timeline seeks FILE-relatively, not book-relatively`() {
        // The fake reports a file position of 40_000 while sitting at 160_000 in the book.
        // With no timeline there is nothing to translate between them, so the only correct
        // answer is the file-relative one.
        val player = FakeExoPlayer(stubbedPosition = 40_000L)
        val callback = callbackWith(player = player, bookPositionMs = 160_000L, timeline = null)

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_BACK)

        // 40_000 − 10_000 = 30_000, file-relative. Had the fallback reached for the book
        // position instead, this would be 150_000 — a seek far past the end of the file.
        player.fileRelativeSeekCalls shouldBe listOf(30_000L)
        player.seekCalls.shouldBeEmpty()
    }

    @Test
    fun `skip forward without a timeline clamps to the file duration`() {
        val player = FakeExoPlayer(stubbedPosition = 40_000L, stubbedDuration = 50_000L)
        val callback = callbackWith(player = player, bookPositionMs = 160_000L, timeline = null)

        callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_FORWARD)

        // 40_000 + 30_000 = 70_000, past the file's 50_000 ms end, so it clamps to the
        // file duration — the file's end, not the book's.
        player.fileRelativeSeekCalls shouldBe listOf(50_000L)
        player.seekCalls.shouldBeEmpty()
    }

    // ── Honest chapter-nav result when there is nothing to do ──────────────────
    //
    // Both branches used to fall through to the shared `return
    // Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))` at the end of
    // onCustomCommand regardless of whether the guard let the seek run — so a head unit was
    // actively told the command worked while the player never moved.

    @Test
    fun `previous chapter with no chapters reports skipped, not success`() {
        val player = FakeExoPlayer()
        val callback =
            callbackWith(player = player, bookPositionMs = 10_000L, timeline = threeFileTimeline(), chapters = emptyList())

        val result = callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_PREV_CHAPTER)

        result.resultCode shouldBe SessionResult.RESULT_INFO_SKIPPED
        player.seekCalls.shouldBeEmpty()
        player.fileRelativeSeekCalls.shouldBeEmpty()
    }

    @Test
    fun `next chapter with no timeline reports skipped, not success`() {
        val player = FakeExoPlayer()
        val callback = callbackWith(player = player, bookPositionMs = 10_000L, timeline = null, chapters = twoChapters())

        val result = callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_NEXT_CHAPTER)

        result.resultCode shouldBe SessionResult.RESULT_INFO_SKIPPED
        player.seekCalls.shouldBeEmpty()
        player.fileRelativeSeekCalls.shouldBeEmpty()
    }

    // ── No transport player ───────────────────────────────────────────────────

    @Test
    fun `a custom command with no transport player errors instead of seeking`() {
        val player = FakeExoPlayer()
        // activeTransportPlayer() is null between a cast handoff and the receiver being
        // ready — the callback must refuse rather than fall back to some other player.
        val callback = callbackWith(player = null, bookPositionMs = 120_000L, timeline = threeFileTimeline())

        val result = callback.invokeCustomCommand(AudiobookNotificationProvider.COMMAND_SKIP_BACK)

        result.resultCode shouldBe SessionResult.RESULT_ERROR_UNKNOWN
        player.seekCalls.shouldBeEmpty()
        player.fileRelativeSeekCalls.shouldBeEmpty()
    }

    // ── Cover art URI grant ───────────────────────────────────────────────────
    //
    // `grantCoverArtAccess` is the branch's highest-risk line: swapping the grantee package
    // and the URI compiles cleanly, crashes nothing, and produces no test failure on its
    // own — just silently blank cover art in a car. `MediaSession.ControllerInfo` is a final
    // Media3 class the test suite cannot construct, so this calls `grantCoverArtAccess`
    // directly (it is `internal` for exactly this) rather than driving it through `onConnect`.

    @Test
    fun `grantCoverArtAccess grants the connecting controller our own cover prefix URI`() {
        val granter = FakeUriPermissionGranter()
        val callback =
            callbackWith(player = null, bookPositionMs = 0L, timeline = null, uriPermissionGranter = granter)
        val controllerPackage = "com.google.android.projection.gearhead"

        callback.grantCoverArtAccess(controllerPackage)

        // Both halves matter: the grantee must be the *controller's* package (not ours), and
        // the URI must be built from *our* package (not the controller's) — a swap of the two
        // arguments would satisfy neither half correctly while still "granting something".
        val (grantedPackage, grantedUri) = granter.grants.single()
        grantedPackage shouldBe controllerPackage
        grantedUri shouldBe CoverUri.prefixUri(context.packageName)
    }

    // ── Signed-out browse gating ──────────────────────────────────────────────
    //
    // #1239 walled off `onGetLibraryRoot`/`onGetChildren`; #1245 closes the three doors it left
    // open. Each is a genuine way into the library that does not pass through the browse tree —
    // a cached mediaId, a voice query, the results of one — so a signed-out session could still
    // resolve a book or run a search around the wall. It failed *soft* rather than loudly, because
    // Room is typically empty when genuinely signed out, which is precisely what made it easy to
    // miss: "no results" reads as a missing book, not as a missing session.

    // ⛔ The one that cost a Play policy rejection (Auto App Quality: "app does not load in the
    // Android Auto environment"). The browse *tree* may be walled off when signed out; the browse
    // *root* may not. A root that errors leaves the browser with no content hierarchy at all, and
    // the head unit answers with "ListenUp doesn't seem to be working right now" — the app reads as
    // broken rather than as signed out, and there is no sign-in prompt to act on.
    //
    // This is not a judgement call. Google's media-browser guidance is explicit: "The onGetRoot()
    // method should quickly return a non-null value. User authentication and other slow processes
    // shouldn't run in onGetRoot()", and a client that may not browse still gets "a non-null
    // BrowserRoot" whose "root ID should represent an empty content hierarchy".
    //
    // NeedsServerUrl is the state a reviewer is guaranteed to be in: ListenUp is self-hosted, so
    // there is no server for them to sign in to, and this was reachable on any fresh install.
    @Test
    fun `onGetLibraryRoot returns a root when signed out, so Auto can connect at all`() {
        val callback = browseCallback(authState = AuthState.NeedsServerUrl)

        val result =
            callback.onLibrarySession { session, browser ->
                callback.onGetLibraryRoot(session, browser, null)
            }

        result.resultCode shouldBe LibraryResult.RESULT_SUCCESS
    }

    // The other half of the same fix: opening the root must not cost us the sign-in prompt. The
    // gate moves down one level, it does not disappear — the car connects, asks for the root's
    // children, and *there* gets the typed error carrying the sign-in action.
    @Test
    fun `onGetChildren still returns the signed-out error, so the car still prompts to sign in`() {
        val callback = browseCallback(authState = AuthState.NeedsServerUrl)

        val result =
            callback.onLibrarySession { session, browser ->
                callback.onGetChildren(session, browser, BrowseTree.ROOT, 0, 20, null)
            }

        result.resultCode shouldBe SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
    }

    @Test
    fun `onGetItem returns the signed-out error instead of resolving a book`() {
        val callback = browseCallback(authState = AuthState.NeedsLogin(openRegistration = false))

        val result =
            callback.onLibrarySession { session, browser ->
                callback.onGetItem(session, browser, BrowseTree.bookId("book1"))
            }

        result.resultCode shouldBe SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
    }

    @Test
    fun `onGetItem resolves normally once authenticated`() {
        // The counter-case that stops the gate from being trivially satisfiable: ROOT resolves
        // straight from the static tree, so a pass here is the gate standing down, not a stub.
        val callback = browseCallback(authState = AuthState.Authenticated(UserId("u1"), SessionId("s1")))

        val result =
            callback.onLibrarySession { session, browser ->
                callback.onGetItem(session, browser, BrowseTree.ROOT)
            }

        result.resultCode shouldBe LibraryResult.RESULT_SUCCESS
    }

    @Test
    fun `onSearch returns the signed-out error instead of accepting the query`() {
        val callback = browseCallback(authState = AuthState.NeedsLogin(openRegistration = false))

        val result =
            callback.onLibrarySession { session, browser ->
                callback.onSearch(session, browser, "the hobbit", null)
            }

        result.resultCode shouldBe SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
    }

    @Test
    fun `onGetSearchResult returns the signed-out error instead of cached results`() {
        val callback = browseCallback(authState = AuthState.PendingApproval(UserId("u1"), "u@example.com"))

        val result =
            callback.onLibrarySession { session, browser ->
                callback.onGetSearchResult(session, browser, "the hobbit", 0, 20, null)
            }

        result.resultCode shouldBe SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
    }

    @Test
    fun `onGetSearchResult answers an empty page once authenticated`() {
        val callback = browseCallback(authState = AuthState.Authenticated(UserId("u1"), SessionId("s1")))

        val result =
            callback.onLibrarySession { session, browser ->
                callback.onGetSearchResult(session, browser, "nothing cached", 0, 20, null)
            }

        result.resultCode shouldBe LibraryResult.RESULT_SUCCESS
        result.value.shouldBeEmpty()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds the callback with [authState] as the only live dependency — every browse gate reads
     * `authSession.authState` and nothing else before deciding.
     *
     * [BrowseTreeProvider] is real (over unstubbed repository mocks) so the authenticated
     * counter-cases can resolve the *static* tree without a single repository call; anything that
     * reached past it would fail loudly rather than answer a default.
     */
    private fun browseCallback(authState: AuthState): ListenUpSessionCallback {
        val authSession = mock<AuthSession>()
        every { authSession.authState } returns MutableStateFlow(authState)

        return ListenUpSessionCallback(
            context = context,
            playbackManager = mock<PlaybackManager>(),
            browseTreeProvider = unreachedBrowseTreeProvider(),
            voiceIntentResolver = unreachedVoiceIntentResolver(),
            homeRepository = mock<HomeRepository>(),
            authSession = authSession,
            positionRepository = mock<PlaybackPositionRepository>(),
            serviceScope = CoroutineScope(Dispatchers.Unconfined),
            transport = FakePlaybackTransport(null, 0L),
            uriPermissionGranter = FakeUriPermissionGranter(),
            strings = SystemStringsHolder(),
            skipIntervals = stockIntervals(),
        )
    }

    /**
     * Runs [block] against a real [MediaLibrarySession] and blocks on its future.
     *
     * The browse callbacks read neither the session nor the controller on the gated path, but
     * Kotlin's non-null intrinsics demand real instances of both. Media3's `@UnstableApi`
     * `Builder(Context, …)` exists for exactly this, and `createTestOnlyControllerInfo` supplies
     * the browser; the session is released before returning.
     */
    private fun <T> ListenUpSessionCallback.onLibrarySession(
        block: (MediaLibrarySession, MediaSession.ControllerInfo) -> ListenableFuture<LibraryResult<T>>,
    ): LibraryResult<T> {
        val session = MediaLibrarySession.Builder(context, sessionPlayer, this).build()
        try {
            return block(session, testControllerInfo()).get()
        } finally {
            session.release()
        }
    }

    private fun testControllerInfo(): MediaSession.ControllerInfo =
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
        )

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
        uriPermissionGranter: UriPermissionGranter = FakeUriPermissionGranter(),
        skipIntervals: SkipIntervalsHolder = stockIntervals(),
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
            uriPermissionGranter = uriPermissionGranter,
            strings = SystemStringsHolder(),
            skipIntervals = skipIntervals,
        )
    }

    /** The stock 30 s forward / 10 s back, for the cases whose subject is not the interval. */
    private fun stockIntervals(): SkipIntervalsHolder =
        intervals(
            forwardSec = PlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC,
            backwardSec = PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC,
        )

    /**
     * A [SkipIntervalsHolder] already following fixed values.
     *
     * `Dispatchers.Unconfined` so a `MutableStateFlow`'s replayed value lands synchronously on
     * `follow` — the holder is fully populated by the time the callback is built, with no
     * scheduler in the way of the assertions.
     */
    private fun intervals(
        forwardSec: Int,
        backwardSec: Int,
    ): SkipIntervalsHolder {
        val preferences = mock<PlaybackPreferences>()
        every { preferences.observeDefaultSkipForwardSec() } returns MutableStateFlow(forwardSec)
        every { preferences.observeDefaultSkipBackwardSec() } returns MutableStateFlow(backwardSec)
        return SkipIntervalsHolder(preferences).apply { follow(CoroutineScope(Dispatchers.Unconfined)) }
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
            strings = SystemStringsHolder(),
        )

    private fun unreachedVoiceIntentResolver(): VoiceIntentResolver =
        VoiceIntentResolver(
            searchRepository = mock<SearchRepository>(),
            homeRepository = mock<HomeRepository>(),
            seriesRepository = mock<SeriesRepository>(),
            bookRepository = mock<BookRepository>(),
        )
}

/** Records every grant so a test can assert exactly which package was granted which URI. */
private class FakeUriPermissionGranter : UriPermissionGranter {
    val grants = mutableListOf<Pair<String, Uri>>()

    override fun grantRead(
        toPackage: String,
        uri: Uri,
    ) {
        grants += toPackage to uri
    }
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
