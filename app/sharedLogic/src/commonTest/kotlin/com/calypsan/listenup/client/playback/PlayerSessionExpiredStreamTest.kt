package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.playback.TimelineFileInput
import com.calypsan.listenup.client.test.fake.positionFor
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val HOUR = 3_600_000L
private const val BOOK_MS = 10 * HOUR

/** When the signed URLs the engine holds stop working. */
private const val EXPIRES_AT = 100 * HOUR

/** One file of the whole book: streamed when [localPath] is null, played from disk otherwise. */
private fun timeline(
    urlsExpireAtMs: Long?,
    localPath: String? = null,
) = PlaybackTimeline.build(
    bookId = BookId("b1"),
    files =
        listOf(
            TimelineFileInput(
                audioFileId = "af-1",
                filename = "b1.m4b",
                format = "m4b",
                durationMs = BOOK_MS,
                size = 1_000L,
                localPath = localPath,
                streamingUrl = if (localPath == null) "https://server/api/v1/audio/b1/af-1?exp=1" else "",
            ),
        ),
    urlsExpireAtMs = urlsExpireAtMs,
)

/** Play "b1" from 1 h holding [held] as its prepared timeline, then pause, with the clock at [nowMs]. */
private fun TestScope.pausedAtOneHour(
    rig: PlayerSessionRig,
    held: PlaybackTimeline,
    nowMs: Long,
) {
    rig.withBook("b1", chapters = emptyList(), durationMs = BOOK_MS)
    rig.engine.preparedPositionMs = HOUR
    runCurrent()
    rig.session.playBook(BookId("b1"), startAtMs = null)
    runCurrent()
    rig.engine.timeline.value = held
    rig.engine.pause()
    rig.nowMs = nowMs
    runCurrent()
}

class PlayerSessionExpiredStreamTest :
    FunSpec({
        test("Play on a paused book whose stream URLs have lapsed prepares it again at the playhead") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    pausedAtOneHour(rig, timeline(urlsExpireAtMs = EXPIRES_AT), nowMs = EXPIRES_AT + HOUR)
                    rig.session.playBook(BookId("b1"), startAtMs = null)
                    rig.engine.loads.map { it.second } shouldContainExactly
                        listOf(PlaybackStart.Resume, PlaybackStart.At(HOUR))
                } finally {
                    rig.close()
                }
            }
        }

        test("URLs within five minutes of lapsing are treated as lapsed: they would die mid-listen") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    pausedAtOneHour(rig, timeline(urlsExpireAtMs = EXPIRES_AT), nowMs = EXPIRES_AT - 60_000L)
                    rig.session.playBook(BookId("b1"), startAtMs = null)
                    rig.engine.loads
                        .last()
                        .second shouldBe PlaybackStart.At(HOUR)
                } finally {
                    rig.close()
                }
            }
        }

        test("URLs still good resume as before: no prepare") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    pausedAtOneHour(rig, timeline(urlsExpireAtMs = EXPIRES_AT), nowMs = EXPIRES_AT - HOUR)
                    rig.session.playBook(BookId("b1"), startAtMs = null)
                    rig.engine.loads.size shouldBe 1
                    rig.engine.calls.takeLast(2) shouldContainExactly listOf("clearError", "play")
                } finally {
                    rig.close()
                }
            }
        }

        test("a downloaded book has nothing to expire: it resumes however long it sat") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    pausedAtOneHour(
                        rig,
                        timeline(urlsExpireAtMs = EXPIRES_AT, localPath = "/books/b1.m4b"),
                        nowMs = EXPIRES_AT + HOUR,
                    )
                    rig.session.playBook(BookId("b1"), startAtMs = null)
                    rig.engine.loads.size shouldBe 1
                    rig.engine.calls.takeLast(2) shouldContainExactly listOf("clearError", "play")
                } finally {
                    rig.close()
                }
            }
        }

        test("a server that sends no expiry: Play resumes exactly as before") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    pausedAtOneHour(rig, timeline(urlsExpireAtMs = null), nowMs = EXPIRES_AT + HOUR)
                    rig.session.playBook(BookId("b1"), startAtMs = null)
                    rig.engine.loads.size shouldBe 1
                    rig.engine.calls.takeLast(2) shouldContainExactly listOf("clearError", "play")
                } finally {
                    rig.close()
                }
            }
        }

        test("the headset's play/pause on a lapsed stream prepares it again at the playhead too") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    pausedAtOneHour(rig, timeline(urlsExpireAtMs = EXPIRES_AT), nowMs = EXPIRES_AT + HOUR)
                    rig.loaded.phase shouldBe PlayerPhase.Paused
                    rig.session.playPause()
                    rig.engine.loads
                        .last()
                        .second shouldBe PlaybackStart.At(HOUR)
                } finally {
                    rig.close()
                }
            }
        }

        test("a lapsed stream with a newer place from another device prepares again at that place") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.positions.setPosition(positionFor("b1", positionMs = HOUR).copy(lastPlayedAtMs = 1_000L))
                    pausedAtOneHour(rig, timeline(urlsExpireAtMs = EXPIRES_AT), nowMs = EXPIRES_AT + HOUR)
                    rig.positions.setPosition(positionFor("b1", positionMs = 3 * HOUR).copy(lastPlayedAtMs = 2_000L))
                    runCurrent()
                    rig.session.playBook(BookId("b1"), startAtMs = null)
                    rig.engine.loads
                        .last()
                        .second shouldBe PlaybackStart.At(3 * HOUR)
                } finally {
                    rig.close()
                }
            }
        }
    })
