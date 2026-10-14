package com.calypsan.listenup.client.presentation.player

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlayerPhase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PlayerProjectionsTest :
    FunSpec({
        // §3.5 "Back N by state" and §4.6's actions, every phase once.
        val table: List<Triple<PlayerPhase, PrimaryControl, Boolean>> =
            listOf(
                Triple(PlayerPhase.Playing, PrimaryControl.Pause, true),
                Triple(PlayerPhase.Paused, PrimaryControl.Play, true),
                Triple(PlayerPhase.Restored, PrimaryControl.Play, true),
                Triple(PlayerPhase.SleepFading, PrimaryControl.Pause, true),
                Triple(PlayerPhase.Preparing, PrimaryControl.Busy, false),
                Triple(PlayerPhase.Buffering, PrimaryControl.Busy, false),
                Triple(PlayerPhase.Finished, PrimaryControl.ListenAgain, false),
                Triple(PlayerPhase.Failed(PlaybackError.ConnectionLost()), PrimaryControl.Retry, false),
                Triple(PlayerPhase.Failed(PlaybackError.Stalled()), PrimaryControl.Retry, false),
                Triple(PlayerPhase.Failed(PlaybackError.CouldNotStart()), PrimaryControl.Retry, false),
                Triple(PlayerPhase.Failed(PlaybackError.UnsupportedFormat()), PrimaryControl.Retry, false),
                Triple(PlayerPhase.Failed(PlaybackError.BlockedInBackground()), PrimaryControl.Play, false),
                Triple(PlayerPhase.Failed(PlaybackError.AutoplayBlocked()), PrimaryControl.TapToPlay, false),
                Triple(PlayerPhase.Failed(AuthError.SessionExpired()), PrimaryControl.SignIn, false),
                Triple(PlayerPhase.Unavailable(PlaybackError.ServerUnreachable()), PrimaryControl.Retry, false),
                Triple(PlayerPhase.Unavailable(PlaybackError.OfflineNotDownloaded()), PrimaryControl.Retry, false),
                Triple(PlayerPhase.Unavailable(AuthError.SessionExpired()), PrimaryControl.SignIn, false),
                Triple(PlayerPhase.Unavailable(PlaybackError.NoLongerAvailable()), PrimaryControl.Close, false),
            )

        table.forEach { (phase, primary, skipBack) ->
            test("$phase → $primary, back N ${if (skipBack) "shown" else "hidden"}") {
                primaryControlFor(phase, isAdmin = false) shouldBe primary
                showsSkipBackFor(phase) shouldBe skipBack
            }
        }

        test("held: Open Inbox for an admin, Close for a member") {
            primaryControlFor(PlayerPhase.Unavailable(BookError.HeldForReview()), isAdmin = true) shouldBe
                PrimaryControl.OpenInbox
            primaryControlFor(PlayerPhase.Unavailable(BookError.HeldForReview()), isAdmin = false) shouldBe
                PrimaryControl.Close
        }

        context("audioSourceOf (§4.7)") {
            fun source(
                phase: PlayerPhase = PlayerPhase.Playing,
                device: String? = null,
                fullyDownloaded: Boolean? = null,
                anyTranscoded: Boolean = false,
                bookDownloaded: Boolean = false,
                online: Boolean = true,
                reachable: Boolean = true,
                offlineCopies: Boolean = true,
            ) = audioSourceOf(
                phase = phase,
                outputDeviceName = device,
                timelineFullyDownloaded = fullyDownloaded,
                anyFileTranscoded = anyTranscoded,
                bookDownloaded = bookDownloaded,
                isDeviceOnline = online,
                isServerReachable = reachable,
                offlineCopiesSupported = offlineCopies,
            )

            test("all local is Downloaded; offline and local is Downloaded · offline") {
                source(fullyDownloaded = true) shouldBe AudioSource.Downloaded
                source(fullyDownloaded = true, online = false) shouldBe AudioSource.DownloadedOffline
            }
            test("a non-local file with an hlsUrl is transcoded; otherwise Streaming") {
                source(fullyDownloaded = false, anyTranscoded = true) shouldBe AudioSource.StreamingTranscoded
                source(fullyDownloaded = false) shouldBe AudioSource.Streaming
            }
            test("before a prepare it reads the download status") {
                source(phase = PlayerPhase.Restored, bookDownloaded = true) shouldBe AudioSource.Downloaded
                source(phase = PlayerPhase.Restored) shouldBe AudioSource.Streaming
            }
            test("casting carries the device name") {
                source(device = "Living Room") shouldBe AudioSource.Casting("Living Room")
            }
            test("held and revoked are not playable") {
                source(phase = PlayerPhase.Unavailable(PlaybackError.NoLongerAvailable())) shouldBe
                    AudioSource.NotPlayable
                source(phase = PlayerPhase.Unavailable(BookError.HeldForReview())) shouldBe AudioSource.NotPlayable
            }
            test("unreachable: Offline on native, Can't reach your server in the browser") {
                source(fullyDownloaded = false, online = false) shouldBe AudioSource.Offline
                source(fullyDownloaded = false, reachable = false) shouldBe AudioSource.Offline
                source(fullyDownloaded = false, reachable = false, offlineCopies = false) shouldBe
                    AudioSource.ServerUnreachable
            }
        }

        context("playerChapterOf (§3.2)") {
            test("a chaptered book counts from 1") {
                val info =
                    PlaybackManager.ChapterInfo(
                        index = 5,
                        title = "Bridge Four",
                        startMs = 100,
                        endMs = 600,
                        remainingMs = 200,
                        totalChapters = 75,
                        isGenericTitle = false,
                    )
                playerChapterOf(info, bookDurationMs = 10_000) shouldBe
                    PlayerChapter(
                        index = 5,
                        number = 6,
                        total = 75,
                        title = "Bridge Four",
                        isGenericTitle = false,
                        isWholeBook = false,
                        startMs = 100,
                        durationMs = 500,
                    )
            }
            test("a chapterless book is one whole-book chapter covering 0..duration") {
                playerChapterOf(null, bookDurationMs = 10_000) shouldBe
                    PlayerChapter(
                        index = 0,
                        number = 1,
                        total = 1,
                        title = "",
                        isGenericTitle = true,
                        isWholeBook = true,
                        startMs = 0,
                        durationMs = 10_000,
                    )
            }
        }

        test("PlayerProgress fractions and remaining") {
            val p =
                PlayerProgress(
                    chapterPositionMs = 250,
                    chapterDurationMs = 1_000,
                    bookPositionMs = 1_100,
                    bookDurationMs = 10_000,
                )
            p.chapterFraction shouldBe 0.25f
            p.bookFraction shouldBe 0.11f
            p.chapterRemainingMs shouldBe 750
            p.bookRemainingMs shouldBe 8_900
            PlayerProgress.Zero.bookFraction shouldBe 0f
        }

        test("PlayerProgress clamps past the end to 1, floors remaining at 0, and a zero duration reads 0") {
            val past = PlayerProgress(chapterPositionMs = 0, chapterDurationMs = 0, bookPositionMs = 300_000, bookDurationMs = 200_000)
            past.bookFraction shouldBe 1f
            past.bookRemainingMs shouldBe 0
            past.chapterFraction shouldBe 0f
            past.chapterRemainingMs shouldBe 0
        }
    })
