package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.data.settings.seedServerUrlFromOrigin
import com.calypsan.listenup.client.di.jsSharedModules
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackState
import com.calypsan.listenup.client.presentation.auth.LoginViewModel
import com.calypsan.listenup.client.presentation.auth.SetupViewModel
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.web.createSqliteWorker
import com.calypsan.listenup.web.di.webPlaybackModule
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.window
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.w3c.dom.Worker
import kotlin.time.Duration.Companion.seconds

/**
 * THE transcode proof, and the reason this branch exists: a real Chromium plays a book it cannot
 * decode, because the server transcoded it on demand into HLS.
 *
 * Every link in that chain is asserted where it can actually break:
 *
 *  - **`prepare()` mints an `hlsUrl`.** The load-bearing one. It proves the server chose
 *    `TranscodeDecision.Transcode` from the capability set a *real browser* reported through
 *    `platformCodecCapabilities()` — a JS `actual` no other lane has ever executed. Every other
 *    lane declares capabilities from a fixture and therefore proves the policy, never the probe.
 *  - **hls.js is what is driving.** Chromium answers `"maybe"` to the HLS MIME while being unable
 *    to decode a byte of it, so an attachment that silently took the native branch looks identical
 *    from the outside until the audio is missing. [HtmlAudioPlayer.usesHlsJs] is that seam.
 *  - **Bytes were actually decoded.** `currentTime` advances on an element producing silence, which
 *    is exactly how this class of gate goes green over work it never did. `webkitAudioDecodedByteCount`
 *    is the observation that cannot be faked by a clock. Sampled twice — once across the opening
 *    stretch, once across the seek — because a single sample spanning both is paid for entirely by
 *    the first and says nothing about the second.
 *  - **A seek reached the element.** Proved by a *rewind*, not by the forward seek: the player
 *    publishes a seek target optimistically, and a book that simply keeps playing arrives past a
 *    forward target on its own — so neither the target nor anything above it can tell a landed seek
 *    from a recorded intent. See [REWIND_TARGET_MS] for the window that can.
 *
 * The direct-play counterpart runs the identical arc over a book the browser *can* decode, and
 * asserts no HLS url was minted at all. Together the two localise a failure: if only the E-AC-3
 * spec fails, negotiation is broken and the player is fine; if both fail, the player is broken.
 *
 * ## Known limitation
 *
 * Both browser lanes run Chromium with `--autoplay-policy=no-user-gesture-required`, so no `play()`
 * here is ever refused. [HtmlAudioPlayer.primeForPlayback] exists for iOS Safari's *transient*
 * activation rule, which Chrome's *sticky* rule makes unobservable — that gesture timing needs a
 * real-device pass and cannot be recovered by reconfiguring this lane.
 *
 * Enabled only when a server was booted, for the same reason `RpcTransportTest` is: both browser
 * lanes compile ONE spec bundle, and each pins its own exact `KOTEST_MIN_TESTS` floor, so neither
 * can silently drop a spec.
 */
class HlsPlaybackTest :
    FunSpec({
        val serverBooted = js("window.__LU_SERVER_URL").unsafeCast<String?>() != null

        // One signed-in, synced graph for both specs. Booting it per test would pay for auth and a
        // full library sync twice over, and the second boot would contend with the first for the
        // same OPFS database file.
        var graph: KoinApplication? = null

        suspend fun listening(): KoinApplication = graph ?: bootSignedInGraph().also { graph = it }

        afterSpec {
            graph?.let { app ->
                app.koin.get<HtmlAudioPlayer>().releasePlayer()
                app.close()
            }
        }

        test("an E-AC-3 book the browser cannot decode plays as a transcoded HLS stream")
            .config(enabled = serverBooted) {
                val app = listening()
                val player = app.koin.get<HtmlAudioPlayer>()

                val prepared = app.startListening(EAC3_BOOK_TITLE)
                val onlyFile = prepared.timeline.files.single()

                withClue("the server was told what this browser can decode, and E-AC-3 is not on it") {
                    onlyFile.hlsUrl.shouldNotBeNull()
                }
                withClue("Chromium claims HLS support it does not have, so hls.js must be driving") {
                    player.usesHlsJs shouldBe true
                }

                val decodedBefore = player.decodedAudioBytes
                player.play()
                val advanced = player.awaitPositionAtLeast(ADVANCE_TARGET_MS)
                withClue("state=${player.state.value}") {
                    advanced shouldBeGreaterThanOrEqual ADVANCE_TARGET_MS
                }
                val decodedAtAdvance = player.decodedAudioBytes
                withClue("a media element advances its clock while producing silence; bytes do not") {
                    decodedAtAdvance shouldBeGreaterThan decodedBefore
                }

                player.seekTo(SEEK_TARGET_MS)
                val sought = player.awaitPositionAtLeast(PAST_SEEK_TARGET_MS)
                withClue("state=${player.state.value}") {
                    sought shouldBeGreaterThanOrEqual PAST_SEEK_TARGET_MS
                }
                // A SECOND byte sample, not a re-read of the first: growth measured from before
                // `play()` is already fully paid for by the 0→2s stretch above, so the entire
                // second half of this spec could be severed and the assertion would still hold.
                // Measured from the advance, it is the fixture's second HLS segment — which only
                // exists once the server transcodes it — that has to load and decode.
                val decodedPastSeek = player.decodedAudioBytes
                withClue("reaching ${PAST_SEEK_TARGET_MS}ms needs a segment the first one does not contain") {
                    decodedPastSeek shouldBeGreaterThan decodedAtAdvance
                }

                // The rewind is what proves a seek reaches the element at all — see REWIND_TARGET_MS.
                player.seekTo(REWIND_TARGET_MS)
                val resumed = player.awaitPositionIn(REWIND_CONFIRM_MS until SEEK_TARGET_MS)
                withClue("state=${player.state.value}") {
                    resumed shouldBeGreaterThanOrEqual REWIND_CONFIRM_MS
                    resumed shouldBeLessThan SEEK_TARGET_MS
                }
                player.pause()
            }

        test("a book the browser can decode plays directly, with no HLS url minted")
            .config(enabled = serverBooted) {
                val app = listening()
                val player = app.koin.get<HtmlAudioPlayer>()

                val prepared = app.startListening(DIRECT_PLAY_BOOK_TITLE)

                withClue("Chromium decodes AAC-LC, so transcoding it would be work for nothing") {
                    prepared.timeline.files.forEach { it.hlsUrl.shouldBeNull() }
                }
                player.usesHlsJs shouldBe false

                val decodedBefore = player.decodedAudioBytes
                player.play()
                val advanced = player.awaitPositionAtLeast(ADVANCE_TARGET_MS)
                withClue("state=${player.state.value}") {
                    advanced shouldBeGreaterThanOrEqual ADVANCE_TARGET_MS
                }

                withClue("a media element advances its clock while producing silence; bytes do not") {
                    player.decodedAudioBytes shouldBeGreaterThan decodedBefore
                }
                player.pause()
            }
    })

/**
 * The one book in the seed library no browser can decode — E-AC-3, 20 seconds, one track. See
 * `SeedLibraryDescriptor.addTranscodeFixtureBook`; the 20 seconds are there so [SEEK_TARGET_MS]
 * still leaves room to play past.
 */
private const val EAC3_BOOK_TITLE = "The Undecodable Hour"

/**
 * The direct-play control for [EAC3_BOOK_TITLE]: a single-file AAC-LC seed book, which Chromium
 * answers `"probably"` for and therefore declares.
 *
 * **Single-file, and that is not incidental.** The scanner records `codec` for a book's FIRST audio
 * file only — every later file in a multi-file book lands with an empty codec, which
 * `TranscodePolicy` reads as "unrecognised" and therefore transcodes. So a multi-track MP3 book
 * mints an `hlsUrl` for tracks 2..n, and asserting "no HLS url" over it would either fail or, worse,
 * be narrowed to track 1 and quietly pin that gap as intended behaviour. A single-file book is the
 * only shape whose whole answer is honest today.
 */
private const val DIRECT_PLAY_BOOK_TITLE = "The Iron Horizon"

/** Far enough in that a stalled element cannot reach it by chance. */
private const val ADVANCE_TARGET_MS = 2_000L

/** Inside the E-AC-3 fixture's second HLS segment, so the seek forces a fresh transcode. */
private const val SEEK_TARGET_MS = 10_000L

/**
 * Waited out past [SEEK_TARGET_MS] rather than to it, because `HtmlAudioPlayer.seekTo` publishes
 * the target position *optimistically* — before it touches the element at all — so `positionMs`
 * already holds [SEEK_TARGET_MS] the instant the call returns. Only the element's own clock,
 * arriving through `publishPosition()`, can carry the flow past what was asked for.
 *
 * ⚠️ That makes this a real observation of *playback*, and still **not** a proof that the seek
 * landed: the fixture keeps playing, so a completely severed `seekTo` reaches this position too —
 * it just takes eight more seconds of audio to get there. Distinguishing the two needs
 * [REWIND_TARGET_MS]. The fixture's 20 seconds are what leave room for both.
 */
private const val PAST_SEEK_TARGET_MS = SEEK_TARGET_MS + ADVANCE_TARGET_MS

/**
 * Where the spec seeks BACKWARD to, and the only assertion here that a seek reached the element.
 *
 * A forward seek cannot be told from ordinary playback without a wall clock — both end up past the
 * target, one sooner than the other — and the optimistic write means the target itself is already
 * reported before anything happens. A rewind has neither problem, because it asks for a position
 * playback has *left behind*:
 *
 *  - the optimistic write reports exactly [REWIND_TARGET_MS] and can never exceed it;
 *  - an element that never received the seek keeps reporting from beyond [PAST_SEEK_TARGET_MS];
 *  - only an element that actually rewound climbs back out through [REWIND_CONFIRM_MS].
 *
 * So a position inside `[REWIND_CONFIRM_MS, SEEK_TARGET_MS)` is reachable by exactly one of the
 * three, and it arrives about a second after the rewind rather than after another eight of audio.
 *
 * Verified by severing `seekTo`'s dispatch entirely: the earlier forward-only version of this spec
 * passed regardless, and this one fails.
 */
private const val REWIND_TARGET_MS = 2_000L

/** One second of real playback past [REWIND_TARGET_MS] — above the optimistic write, far below the seek. */
private const val REWIND_CONFIRM_MS = REWIND_TARGET_MS + 1_000L

private val AUTH_TIMEOUT = 20.seconds

/** Longer than the auth wait: a first sync seeds a whole library, not a single round trip. */
private val SYNC_TIMEOUT = 60.seconds

/**
 * Longer still, and for a reason unique to this spec: the first request for a segment spawns
 * FFmpeg and waits for it. Every other wait here is a round trip; this one is an encode.
 */
private val PLAYBACK_TIMEOUT = 60.seconds

private const val PROBE_EMAIL = "probe-admin@example.invalid"
private const val PROBE_PASSWORD = "probe-admin-password-1"
private const val PROBE_FIRST_NAME = "Probe"
private const val PROBE_LAST_NAME = "Admin"

/**
 * The browser's own graph — `jsSharedModules()` plus the app shell's [webPlaybackModule], which is
 * what binds [HtmlAudioPlayer] and flips `playbackAvailable` on — signed in and synced.
 *
 * **Order-independent by construction**, exactly as `probeLibrarySync` is: `AuthArcTest` may or may
 * not have already created the first admin, and both specs compile into one bundle with no ordering
 * guarantee. So this signs in when the server has users and sets up when it does not.
 */
private suspend fun bootSignedInGraph(): KoinApplication {
    val app =
        koinApplication {
            allowOverride(true)
            modules(
                jsSharedModules() + webPlaybackModule + module { single<Worker> { createSqliteWorker() } },
            )
        }

    // Seed the server URL before anything touches the network — an unseeded ServerConfig fails
    // every call as "network unavailable" on a machine whose network is fine.
    val serverConfig = app.koin.get<ServerConfig>()
    if (!serverConfig.hasServerConfigured()) {
        serverConfig.setServerUrl(
            ServerUrl(seedServerUrlFromOrigin(stored = null, origin = window.location.origin)),
        )
    }

    val authSession = app.koin.get<AuthSession>()
    authSession.initializeAuthState()
    if (authSession.authState.value is AuthState.NeedsSetup) {
        app.koin.get<SetupViewModel>().onSetupSubmit(
            firstName = PROBE_FIRST_NAME,
            lastName = PROBE_LAST_NAME,
            email = PROBE_EMAIL,
            password = PROBE_PASSWORD,
            passwordConfirm = PROBE_PASSWORD,
        )
    } else {
        app.koin.get<LoginViewModel>().onLoginSubmit(email = PROBE_EMAIL, password = PROBE_PASSWORD)
    }

    val authenticated =
        withTimeoutOrNull(AUTH_TIMEOUT) {
            authSession.authState.filterIsInstance<AuthState.Authenticated>().first()
        }
    checkNotNull(authenticated) { "never reached Authenticated within $AUTH_TIMEOUT" }

    app.koin.get<SyncRepository>().connectRealtime()
    return app
}

/**
 * Prepare [title] and hand it to the player — the production arc, taken through the same
 * `PlaybackManager` / `PlaybackController` pair `LivePlayback` uses, so what is proved here is what
 * a tap on Play actually does.
 */
private suspend fun KoinApplication.startListening(title: String): PlaybackManager.PrepareResult {
    val bookId = awaitBookId(title)
    val manager = koin.get<PlaybackManager>()
    val prepared = manager.prepareForPlayback(bookId)
    checkNotNull(prepared) { "prepare() returned nothing for '$title'" }
    manager.activateBook(bookId)
    koin.get<PlaybackController>().startPlayback(prepared)
    return prepared
}

/**
 * The id of the synced book called [title], read from the browser's OWN Room store.
 *
 * A miss names every title that did sync: the harness forwards only TeamCity messages, so a bare
 * timeout would say nothing about whether the library arrived at all or merely arrived renamed.
 */
private suspend fun KoinApplication.awaitBookId(title: String): BookId {
    val repository = koin.get<BookRepository>()
    val found =
        withTimeoutOrNull(SYNC_TIMEOUT) {
            repository
                .observeBookListItems()
                .map { books -> books.firstOrNull { it.title == title } }
                .filterNotNull()
                .first()
        }
    if (found == null) {
        val synced = repository.observeBookListItems().first().map { it.title }
        error("'$title' never synced within $SYNC_TIMEOUT; the local store holds $synced")
    }
    return found.id
}

/**
 * Wait until the book-relative position reaches [atLeastMs], then report where it actually got to.
 *
 * Returns early on [PlaybackState.Error] rather than sitting out the whole budget: a codec the
 * browser refused, or a fatal hls.js teardown, should read as a failed assertion naming the state,
 * not as a timeout naming nothing.
 */
private suspend fun HtmlAudioPlayer.awaitPositionAtLeast(atLeastMs: Long): Long = awaitPositionMatching { it >= atLeastMs }

/**
 * Wait until the book-relative position falls inside [window], then report where it actually is.
 *
 * A two-sided window rather than a floor, because the failure being ruled out is an element that
 * never received a seek and is therefore reporting from *beyond* it — see [REWIND_TARGET_MS].
 */
private suspend fun HtmlAudioPlayer.awaitPositionIn(window: LongRange): Long = awaitPositionMatching { it in window }

/**
 * The shared wait: settle when [predicate] accepts the published position, or when the player
 * reports an error.
 *
 * Returning early on [PlaybackState.Error] rather than sitting out the whole budget is the
 * difference between a failure that names the codec the browser refused and one that names nothing.
 */
private suspend fun HtmlAudioPlayer.awaitPositionMatching(predicate: (Long) -> Boolean): Long {
    withTimeoutOrNull(PLAYBACK_TIMEOUT) {
        combine(positionMs, state) { position, playbackState ->
            predicate(position) || playbackState is PlaybackState.Error
        }.first { it }
    }
    return positionMs.value
}
