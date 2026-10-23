package com.calypsan.listenup.client.presentation.player

import app.cash.turbine.Event
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.playback.EnginePhase
import com.calypsan.listenup.client.playback.PLAYBACK_DROP_GRACE
import com.calypsan.listenup.client.playback.PREPARING_UI_DELAY
import com.calypsan.listenup.client.playback.PlaybackStart
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.PlayerSessionState
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.test.fake.positionFor
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

class PlayerViewModelTest :
    FunSpec({
        // viewModelScope runs on Main; runTest adopts Main's scheduler, so the VM, the session and the test share one clock.
        beforeTest { Dispatchers.setMain(StandardTestDispatcher()) }
        afterTest { Dispatchers.resetMain() }

        test("fresh install: NothingLoaded") {
            runTest {
                val rig = PlayerViewModelRig(this)
                try {
                    rig.vm.uiState.test {
                        awaitItem() shouldBe PlayerUiState.NothingLoaded
                        runCurrent()
                        expectNoEvents()
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("cold start: the restored book with its metadata, its chapter at the saved position, Play and back N") {
            runTest {
                val rig = PlayerViewModelRig(this, storedBookId = "b1")
                try {
                    rig.s.withBook("b1")
                    rig.s.positions.setPosition(positionFor("b1", positionMs = 150_000))
                    rig.vm.uiState.test {
                        runCurrent() // starts the subscription's upstream; moves no virtual time
                        val loaded = expectMostRecentLoaded()
                        loaded.book.id shouldBe "b1"
                        loaded.phase shouldBe PlayerPhase.Restored
                        loaded.primary shouldBe PrimaryControl.Play
                        loaded.showsSkipBack shouldBe true
                        loaded.chapter.number shouldBe 2
                        loaded.chapters.single { it.isCurrent }.index shouldBe loaded.chapter.index
                        loaded.canSkipToNextChapter shouldBe true
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("the book's identity comes from Room: title, cover, byline and duration") {
            runTest {
                val rig = PlayerViewModelRig(this, storedBookId = "b1")
                try {
                    rig.s.withBook("b1")
                    rig.vm.uiState.test {
                        runCurrent() // starts the subscription's upstream; moves no virtual time
                        val book = expectMostRecentLoaded().book
                        book.title shouldBe "The Great Gatsby"
                        book.coverPath shouldBe "/covers/gatsby.jpg"
                        book.authors shouldBe listOf(PlayerContributor("author-b1", "F. Scott Fitzgerald", 0))
                        book.narrators shouldBe listOf(PlayerContributor("narrator-b1", "Jake Gyllenhaal", 0))
                        book.durationMs shouldBe 300_000
                        book.series shouldBe emptyList()
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("a book in two series exposes both, in the book's order, with their places") {
            runTest {
                val rig = PlayerViewModelRig(this, storedBookId = "b1")
                try {
                    rig.s.withBook("b1")
                    rig.s.books.setBookDetail(
                        TestData.bookDetail(id = "b1", duration = 300_000).copy(
                            series =
                                listOf(
                                    BookSeries(seriesId = "s-cosmere", seriesName = "The Cosmere", sequence = 7.0),
                                    BookSeries(seriesId = "s-mistborn", seriesName = "Mistborn", sequence = 2.5),
                                ),
                        ),
                    )
                    rig.vm.uiState.test {
                        runCurrent() // starts the subscription's upstream; moves no virtual time
                        expectMostRecentLoaded().book.series shouldContainExactly
                            listOf(
                                PlayerSeriesRef(id = "s-cosmere", name = "The Cosmere", sequenceLabel = "7"),
                                PlayerSeriesRef(id = "s-mistborn", name = "Mistborn", sequenceLabel = "2.5"),
                            )
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("playBook(B) while A plays: the new book's title from Room at once, Preparing, Busy (§3.6)") {
            runTest {
                val rig = PlayerViewModelRig(this)
                try {
                    rig.s.withBook("a")
                    rig.s.withBook("b")
                    rig.vm.uiState.test {
                        rig.vm.playBook(BookId("a"), null)
                        runCurrent()
                        rig.s.engine.loadSuspension = { CompletableDeferred<Unit>().await() }
                        rig.vm.playBook(BookId("b"), null)
                        runCurrent()
                        val loaded = expectMostRecentLoaded()
                        loaded.book.id shouldBe "b"
                        loaded.phase shouldBe PlayerPhase.Preparing
                        loaded.primary shouldBe PrimaryControl.Busy
                        loaded.showsSkipBack shouldBe false
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("Next is disabled at the last chapter") {
            runTest {
                val rig = PlayerViewModelRig(this)
                try {
                    rig.s.withBook("b1")
                    rig.vm.uiState.test {
                        rig.vm.playBook(BookId("b1"), 250_000)
                        runCurrent()
                        expectMostRecentLoaded().canSkipToNextChapter shouldBe false
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("finished: FinishedInfo with dates and Listen again; listenAgain prepares the same book") {
            runTest {
                val rig = PlayerViewModelRig(this, storedBookId = "b1")
                try {
                    rig.s.withBook("b1")
                    rig.s.positions.setPosition(
                        positionFor("b1", positionMs = 300_000, isFinished = true, finishedAtMs = 2_000, startedAtMs = 1_000),
                    )
                    rig.vm.uiState.test {
                        runCurrent() // starts the subscription's upstream; moves no virtual time
                        val loaded = expectMostRecentLoaded()
                        loaded.phase shouldBe PlayerPhase.Finished
                        loaded.primary shouldBe PrimaryControl.ListenAgain
                        loaded.finished shouldBe
                            FinishedInfo(
                                finishedAtMs = 2_000,
                                startedAtMs = 1_000,
                                durationMs = 300_000,
                                yourHalfStars = null,
                                nextBook = null,
                            )
                        rig.vm.listenAgain()
                        runCurrent()
                        rig.s.engine.loads
                            .single() shouldBe (BookId("b1") to PlaybackStart.Resume)
                        cancelAndIgnoreRemainingEvents()
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("an engine error shows Failed with Retry; dismissError keeps the bar") {
            runTest {
                val rig = PlayerViewModelRig(this)
                try {
                    rig.s.withBook("b1")
                    rig.vm.uiState.test {
                        rig.vm.playBook(BookId("b1"), null)
                        runCurrent()
                        rig.s.engine.phase.value = EnginePhase.IDLE
                        rig.s.engine.error.value = PlaybackError.ConnectionLost()
                        runCurrent()
                        advanceTimeBy(PLAYBACK_DROP_GRACE)
                        runCurrent()
                        val failed = expectMostRecentLoaded()
                        failed.phase shouldBe PlayerPhase.Failed(PlaybackError.ConnectionLost())
                        failed.primary shouldBe PrimaryControl.Retry
                        rig.vm.dismissError()
                        runCurrent()
                        expectMostRecentLoaded().phase shouldBe PlayerPhase.Paused
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("the SignIn primary emits OpenSignIn; Close book collapses and unloads") {
            runTest {
                val rig = PlayerViewModelRig(this, storedBookId = "b1")
                try {
                    rig.s.withBook("b1")
                    rig.s.auth.authState.value = AuthState.SessionLapsed(userId = UserId("u1"))
                    rig.vm.events.test {
                        rig.vm.uiState.test {
                            runCurrent() // starts the subscription's upstream; moves no virtual time
                            expectMostRecentLoaded().primary shouldBe PrimaryControl.SignIn
                            cancelAndIgnoreRemainingEvents()
                        }
                        rig.vm.onPrimary()
                        awaitItem() shouldBe PlayerEvent.OpenSignIn
                    }
                    rig.vm.expand()
                    rig.vm.closeBook()
                    runCurrent()
                    rig.sheet.expanded.value shouldBe false
                    rig.s.session.state.value shouldBe PlayerSessionState.NothingLoaded
                } finally {
                    rig.close()
                }
            }
        }

        test("card actions: Dismiss keeps the book paused, Retry prepares again, Close book unloads") {
            runTest {
                val rig = PlayerViewModelRig(this)
                try {
                    rig.s.withBook("b1")
                    rig.vm.uiState.test {
                        rig.vm.playBook(BookId("b1"), null)
                        runCurrent()
                        rig.s.engine.phase.value = EnginePhase.IDLE
                        rig.s.engine.error.value = PlaybackError.ConnectionLost()
                        runCurrent()
                        advanceTimeBy(PLAYBACK_DROP_GRACE)
                        runCurrent()
                        val card = PlayerCards.of(expectMostRecentLoaded()).shouldBeInstanceOf<PlayerCard.Problem>()
                        rig.vm.onCardAction(card.secondaryAction.shouldNotBeNull())
                        runCurrent()
                        expectMostRecentLoaded().phase shouldBe PlayerPhase.Paused

                        rig.s.engine.error.value = PlaybackError.ConnectionLost()
                        runCurrent()
                        advanceTimeBy(PLAYBACK_DROP_GRACE)
                        runCurrent()
                        rig.vm.onCardAction(PlayerCardAction.Retry)
                        runCurrent()
                        expectMostRecentLoaded().phase shouldNotBe PlayerPhase.Failed(PlaybackError.ConnectionLost())

                        rig.vm.onCardAction(PlayerCardAction.CloseBook)
                        runCurrent()
                        expectMostRecentItem() shouldBe PlayerUiState.NothingLoaded
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("Keep listening on the fade card cancels the timer and puts full volume back (PT7)") {
            runTest {
                val rig = PlayerViewModelRig(this)
                try {
                    rig.s.withBook("b1")
                    rig.vm.uiState.test {
                        rig.vm.playBook(BookId("b1"), null)
                        runCurrent()
                        rig.vm.setSleepTimer(SleepTimerMode.Duration(1))
                        advanceTimeBy(61_000)
                        runCurrent()
                        advanceTimeBy(500)
                        runCurrent()
                        PlayerCards.of(expectMostRecentLoaded()) shouldBe PlayerCard.SleepFading
                        rig.vm.onCardAction(PlayerCardAction.KeepListening)
                        runCurrent()
                        rig.s.sleep.state.value shouldBe SleepTimerState.Inactive
                        rig.s.engine.calls.last() shouldBe "setVolume:1.0"
                        expectMostRecentLoaded().phase shouldBe PlayerPhase.Playing
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("card actions: Sign in goes through the primary, so it emits OpenSignIn") {
            runTest {
                val rig = PlayerViewModelRig(this, storedBookId = "b1")
                try {
                    rig.s.withBook("b1")
                    rig.s.auth.authState.value = AuthState.SessionLapsed(userId = UserId("u1"))
                    rig.vm.events.test {
                        rig.vm.uiState.test {
                            runCurrent()
                            expectMostRecentLoaded().primary shouldBe PrimaryControl.SignIn
                            cancelAndIgnoreRemainingEvents()
                        }
                        rig.vm.onCardAction(PlayerCardAction.SignIn)
                        awaitItem() shouldBe PlayerEvent.OpenSignIn
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("the source reads Casting with the device name") {
            runTest {
                val rig = PlayerViewModelRig(this)
                try {
                    rig.s.withBook("b1")
                    rig.vm.uiState.test {
                        rig.vm.playBook(BookId("b1"), null)
                        rig.s.engine.outputDeviceName.value = "Living Room"
                        runCurrent()
                        expectMostRecentLoaded().source shouldBe AudioSource.Casting("Living Room")
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("settings reflect the engine's boost and the user's default") {
            runTest {
                val rig = PlayerViewModelRig(this)
                try {
                    rig.s.withBook("b1")
                    rig.s.preferences.defaultBoostDb.value = 3f
                    rig.vm.uiState.test {
                        rig.vm.playBook(BookId("b1"), null)
                        runCurrent()
                        rig.vm.setBoost(6f)
                        runCurrent()
                        val settings = expectMostRecentLoaded().settings
                        settings.boostDb shouldBe 6f
                        settings.defaultBoostDb shouldBe 3f
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("preparingBookId names a slow prepare only after the debounce, and clears when it lands") {
            runTest {
                val rig = PlayerViewModelRig(this)
                try {
                    rig.s.withBook("b1")
                    val gate = CompletableDeferred<Unit>()
                    rig.s.engine.loadSuspension = { gate.await() }
                    rig.vm.preparingBookId.test {
                        awaitItem() shouldBe null
                        rig.vm.playBook(BookId("b1"), null)
                        advanceTimeBy(PREPARING_UI_DELAY.inWholeMilliseconds - 1)
                        runCurrent()
                        expectNoEvents()
                        advanceTimeBy(2)
                        runCurrent()
                        awaitItem() shouldBe "b1"
                        gate.complete(Unit)
                        runCurrent()
                        awaitItem() shouldBe null
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("two view model instances share one expanded flag") {
            runTest {
                val rig = PlayerViewModelRig(this)
                try {
                    val other = rig.newVm()
                    rig.vm.expand()
                    other.expanded.value shouldBe true
                    other.collapse()
                    rig.vm.expanded.value shouldBe false
                } finally {
                    rig.close()
                }
            }
        }

        test("seekToChapter closes the chapter sheet (PC4)") {
            runTest {
                val rig = PlayerViewModelRig(this)
                try {
                    rig.s.withBook("b1")
                    rig.vm.playBook(BookId("b1"), null)
                    runCurrent()
                    rig.vm.showChapterPicker()
                    rig.vm.seekToChapter(2)
                    rig.vm.overlay.value shouldBe PlayerOverlay.None
                    rig.s.engine.calls
                        .last() shouldBe "seekTo:200000"
                } finally {
                    rig.close()
                }
            }
        }

        test("each picker opens and hideOverlay closes it") {
            runTest {
                val rig = PlayerViewModelRig(this)
                try {
                    val opens: List<Pair<() -> Unit, PlayerOverlay>> =
                        listOf(
                            rig.vm::showChapterPicker to PlayerOverlay.ChapterPicker,
                            rig.vm::showSpeedPicker to PlayerOverlay.SpeedPicker,
                            rig.vm::showBoostPicker to PlayerOverlay.BoostPicker,
                            rig.vm::showSleepTimer to PlayerOverlay.SleepTimer,
                            { rig.vm.showContributorPicker(ContributorPickerType.NARRATORS) } to
                                PlayerOverlay.ContributorPicker(ContributorPickerType.NARRATORS),
                        )
                    opens.forEach { (open, overlay) ->
                        open()
                        rig.vm.overlay.value shouldBe overlay
                        rig.vm.hideOverlay()
                        rig.vm.overlay.value shouldBe PlayerOverlay.None
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("switching books never passes through NothingLoaded while the new book's metadata loads (§3.6)") {
            runTest {
                val rig = PlayerViewModelRig(this)
                try {
                    rig.s.withBook("a")
                    rig.s.withBook("b")
                    rig.vm.uiState.test {
                        rig.vm.playBook(BookId("a"), null)
                        runCurrent()
                        expectMostRecentLoaded().book.id shouldBe "a"

                        rig.documentGates["b"] = CompletableDeferred()
                        rig.s.engine.loadSuspension = { CompletableDeferred<Unit>().await() }
                        rig.vm.playBook(BookId("b"), null)
                        runCurrent()
                        // B's metadata is still in flight: the bar must not unmount.
                        rig.vm.uiState.value
                            .shouldBeInstanceOf<PlayerUiState.Loaded>()

                        rig.documentGates.getValue("b").complete(Unit)
                        runCurrent()
                        val emitted =
                            cancelAndConsumeRemainingEvents()
                                .filterIsInstance<Event.Item<PlayerUiState>>()
                                .map { it.value }
                        emitted.none { it is PlayerUiState.NothingLoaded } shouldBe true
                        (emitted.last() as PlayerUiState.Loaded).book.id shouldBe "b"
                    }
                } finally {
                    rig.close()
                }
            }
        }

        context("Open PDF") {
            val pdf = BookDocument(id = "doc-pdf-1", index = 1, filename = "guide.pdf", format = "pdf", size = 1_000L, hash = "abc")
            val epub = BookDocument(id = "doc-epub", index = 0, filename = "book.epub", format = "epub", size = 1_000L, hash = "def")

            test("firstPdfDocId is null without a PDF, and the first PDF's id with one") {
                runTest {
                    val rig = PlayerViewModelRig(this, storedBookId = "b1")
                    try {
                        rig.s.withBook("b1")
                        rig.documentList.value = listOf(epub)
                        rig.vm.uiState.test {
                            runCurrent() // starts the subscription's upstream; moves no virtual time
                            expectMostRecentLoaded().book.firstPdfDocId shouldBe null
                            rig.documentList.value = listOf(epub, pdf)
                            runCurrent()
                            expectMostRecentLoaded().book.firstPdfDocId shouldBe "doc-pdf-1"
                        }
                    } finally {
                        rig.close()
                    }
                }
            }

            test("openCurrentPdf emits OpenDocumentViewer with the local path") {
                runTest {
                    val rig = PlayerViewModelRig(this, storedBookId = "b1")
                    try {
                        rig.s.withBook("b1")
                        rig.documentList.value = listOf(pdf)
                        rig.ensureLocalResult = AppResult.Success("/cache/books/b1/doc-pdf-1.pdf")
                        rig.vm.uiState.test {
                            runCurrent() // starts the subscription's upstream; moves no virtual time
                            expectMostRecentLoaded()
                            rig.vm.events.test {
                                rig.vm.openCurrentPdf()
                                runCurrent()
                                awaitItem() shouldBe PlayerEvent.OpenDocumentViewer("/cache/books/b1/doc-pdf-1.pdf")
                            }
                        }
                    } finally {
                        rig.close()
                    }
                }
            }

            test("a PDF that can't be fetched goes to the error bus") {
                runTest {
                    val rig = PlayerViewModelRig(this, storedBookId = "b1")
                    try {
                        val error = TransportError.NetworkUnavailable()
                        rig.s.withBook("b1")
                        rig.documentList.value = listOf(pdf)
                        rig.ensureLocalResult = AppResult.Failure(error)
                        rig.vm.uiState.test {
                            runCurrent() // starts the subscription's upstream; moves no virtual time
                            expectMostRecentLoaded()
                            rig.errorBus.errors.test {
                                rig.vm.openCurrentPdf()
                                runCurrent()
                                awaitItem() shouldBe error
                            }
                        }
                    } finally {
                        rig.close()
                    }
                }
            }
        }
    })

/** Return the newest item received so far, which must be Loaded. */
private fun ReceiveTurbine<PlayerUiState>.expectMostRecentLoaded(): PlayerUiState.Loaded =
    expectMostRecentItem().shouldBeInstanceOf<PlayerUiState.Loaded>()
