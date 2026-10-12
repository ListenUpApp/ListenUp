package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.test.fake.positionFor
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PlayerSessionLifecycleTest :
    FunSpec({
        test("fresh install: Restoring, then NothingLoaded") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.session.state.value shouldBe PlayerSessionState.Restoring
                    runCurrent()
                    rig.session.state.value shouldBe PlayerSessionState.NothingLoaded
                } finally {
                    rig.close()
                }
            }
        }

        test("cold start restores the loaded book paused at its saved position, without touching the engine (S3)") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b1")
                try {
                    rig.withBook("b1")
                    rig.positions.setPosition(positionFor("b1", positionMs = 150_000))
                    runCurrent()
                    rig.loaded.bookId shouldBe BookId("b1")
                    rig.loaded.phase shouldBe PlayerPhase.Restored
                    rig.loaded.isEngineBound shouldBe false
                    rig.session.positionMs.value shouldBe 150_000
                    rig.session.currentChapter.value!!
                        .index shouldBe 1
                    rig.engine.loads shouldBe emptyList()
                    rig.engine.calls shouldBe emptyList()
                } finally {
                    rig.close()
                }
            }
        }

        test("cold start on a finished book reads Finished") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b1")
                try {
                    rig.withBook("b1")
                    rig.positions.setPosition(positionFor("b1", positionMs = 300_000, isFinished = true))
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Finished
                } finally {
                    rig.close()
                }
            }
        }

        test("a persisted id whose row is gone unloads and clears the key") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "gone")
                try {
                    runCurrent()
                    rig.session.state.value shouldBe PlayerSessionState.NothingLoaded
                    rig.store.stored.shouldBeNull()
                } finally {
                    rig.close()
                }
            }
        }

        test("a revoked row that isn't downloaded restores as No longer in your library; Close then clears it") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b1")
                try {
                    rig.withBook("b1")
                    rig.books.setBooks(emptyList()) // tombstoned: the row stays, it is no longer live
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Unavailable(PlaybackError.NoLongerAvailable())
                    rig.store.stored shouldBe "b1"
                    rig.session.closeBook()
                    runCurrent()
                    rig.session.state.value shouldBe PlayerSessionState.NothingLoaded
                    rig.store.stored.shouldBeNull()
                } finally {
                    rig.close()
                }
            }
        }

        test("a revoke while streaming stops the engine but keeps the book loaded until Close") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), startAtMs = null)
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                    rig.books.setBooks(emptyList())
                    runCurrent()
                    rig.engine.calls shouldContain "stop"
                    rig.loaded.phase shouldBe PlayerPhase.Unavailable(PlaybackError.NoLongerAvailable())
                    rig.store.stored shouldBe "b1"
                } finally {
                    rig.close()
                }
            }
        }

        test("a revoked book that is downloaded and in progress keeps playing") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    rig.downloads.setStatus("b1", BookDownloadStatus.Completed(bookId = "b1", totalBytes = 1))
                    rig.positions.setPosition(positionFor("b1", positionMs = 5_000))
                    runCurrent()
                    rig.session.playBook(BookId("b1"), startAtMs = null)
                    runCurrent()
                    rig.books.setBooks(emptyList())
                    runCurrent()
                    rig.engine.calls shouldNotContain "stop"
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                } finally {
                    rig.close()
                }
            }
        }

        test("the engine starting another book out of band (Android Auto, resumption) makes it the loaded book") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b1")
                try {
                    rig.withBook("b1")
                    rig.withBook("b2")
                    runCurrent()
                    rig.engine.loadedBookId.value = BookId("b2")
                    rig.engine.phase.value = EnginePhase.PLAYING
                    runCurrent()
                    rig.loaded.bookId shouldBe BookId("b2")
                    rig.loaded.isEngineBound shouldBe true
                    rig.loaded.phase shouldBe PlayerPhase.Playing
                    rig.store.stored shouldBe "b2"
                } finally {
                    rig.close()
                }
            }
        }

        test("the engine dropping the book (service killed) falls back to Restored, not to nothing") {
            runTest {
                val rig = PlayerSessionRig(this)
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.session.playBook(BookId("b1"), startAtMs = null)
                    runCurrent()
                    rig.engine.loadedBookId.value = null
                    runCurrent()
                    rig.loaded.phase shouldBe PlayerPhase.Restored
                    rig.loaded.isEngineBound shouldBe false
                } finally {
                    rig.close()
                }
            }
        }

        // Enabled in Task 17, which adds PlayerSession.setSleepTimer (playBook arrives in Task 14).
        xtest("Close book stops, clears the key, cancels the timer and leaves nothing loaded") {}

        test("someone else clearing the key (sign-out) unloads") {
            runTest {
                val rig = PlayerSessionRig(this, storedBookId = "b1")
                try {
                    rig.withBook("b1")
                    runCurrent()
                    rig.loadedBooks.clear()
                    runCurrent()
                    rig.session.state.value shouldBe PlayerSessionState.NothingLoaded
                } finally {
                    rig.close()
                }
            }
        }
    })
