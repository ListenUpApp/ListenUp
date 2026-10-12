package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PlayerSessionErrorsTest :
    FunSpec({
        test("an engine failure mid-play is Failed with the typed error; dismiss pauses and keeps the book") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.engine.error.value = PlaybackError.ConnectionLost()
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Failed(PlaybackError.ConnectionLost())
                    rig.session.dismissError()
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Paused
                } finally {
                    rig.close()
                }
            }
        }

        test("a failed prepare dismisses to Restored, never to nothing") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    rig.engine.loadResult = AppResult.Failure(PlaybackError.CouldNotStart())
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.session.dismissError()
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Restored
                } finally {
                    rig.close()
                }
            }
        }

        test("Retry on a transport failure re-probes the server and re-prepares at the saved position") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.engine.error.value = PlaybackError.ConnectionLost()
                    runCurrent()
                    rig.session.retry()
                    runCurrent()
                    rig.reachability.retryCalls shouldBe 1
                    rig.engine.loads.last() shouldBe (BookId("b1") to null)
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                } finally {
                    rig.close()
                }
            }
        }

        test("offline with nothing downloaded is proactive OfflineNotDownloaded, and clears when the network returns") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b1")
                try {
                    rig.withBook("b1")
                    rig.network.onlineFlow.value = false
                    rig.availability.stateFlow.value =
                        rig.availability.stateFlow.value
                            .copy(canPlay = false)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Unavailable(PlaybackError.OfflineNotDownloaded())
                    rig.network.onlineFlow.value = true
                    rig.availability.stateFlow.value =
                        rig.availability.stateFlow.value
                            .copy(canPlay = true)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Restored
                } finally {
                    rig.close()
                }
            }
        }

        test("Retry while still offline keeps saying offline, not that the book couldn't play") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b1")
                try {
                    rig.withBook("b1")
                    rig.network.onlineFlow.value = false
                    rig.availability.stateFlow.value =
                        rig.availability.stateFlow.value
                            .copy(canPlay = false)
                    rig.engine.loadResult = AppResult.Failure(PlaybackError.CouldNotStart())
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Unavailable(PlaybackError.OfflineNotDownloaded())
                    rig.session.retry()
                    runCurrent()
                    rig.engine.loads.last() shouldBe (BookId("b1") to null)
                    rig.loaded.phase shouldBe PlayerPhase.Unavailable(PlaybackError.OfflineNotDownloaded())
                    rig.network.onlineFlow.value = true
                    rig.availability.stateFlow.value =
                        rig.availability.stateFlow.value
                            .copy(canPlay = true)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Restored
                } finally {
                    rig.close()
                }
            }
        }

        test("a failed prepare with nothing standing in the book's way is the typed failure") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    rig.engine.loadResult = AppResult.Failure(PlaybackError.CouldNotStart())
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Failed(PlaybackError.CouldNotStart())
                } finally {
                    rig.close()
                }
            }
        }

        test("the browser says server unreachable where a phone says offline") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b1", offlineCopiesSupported = false)
                try {
                    rig.withBook("b1")
                    rig.network.onlineFlow.value = false
                    rig.availability.stateFlow.value =
                        rig.availability.stateFlow.value
                            .copy(canPlay = false)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Unavailable(PlaybackError.ServerUnreachable())
                } finally {
                    rig.close()
                }
            }
        }

        test("a lapsed session is SessionExpired only when the book has no downloaded audio") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b1")
                try {
                    rig.withBook("b1")
                    rig.auth.authState.value = AuthState.SessionLapsed(userId = UserId("u1"))
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Unavailable(AuthError.SessionExpired())
                    rig.availability.stateFlow.value =
                        rig.availability.stateFlow.value.copy(
                            downloadStatus = BookDownloadStatus.Completed(bookId = "b1", totalBytes = 1),
                        )
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Restored
                } finally {
                    rig.close()
                }
            }
        }

        test("a held book is Unavailable(HeldForReview) and Retry does nothing") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b1")
                try {
                    rig.withBook("b1")
                    rig.inbox.hold("b1")
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Unavailable(BookError.HeldForReview())
                    rig.session.retry()
                    runCurrent()
                    rig.engine.loads shouldBe emptyList()
                } finally {
                    rig.close()
                }
            }
        }

        test("Play on an autoplay-blocked book plays directly (web), no re-prepare") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.engine.error.value = PlaybackError.AutoplayBlocked()
                    runCurrent()
                    rig.session.playPause()
                    rig.engine.calls.takeLast(2) shouldContainExactly listOf("clearError", "play")
                    rig.engine.loads.size shouldBe 1
                } finally {
                    rig.close()
                }
            }
        }
    })
