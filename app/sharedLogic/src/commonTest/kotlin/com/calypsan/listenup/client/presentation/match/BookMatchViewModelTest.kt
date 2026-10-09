package com.calypsan.listenup.client.presentation.match

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FindStrategy
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.SourceStatus
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.test.fake.FakeBookRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * [BookMatchViewModel] — one Match details session. The behaviours that matter are the ones today's two-VM wizard
 * gets wrong: Back re-searching, choices lost on return, a two-pane layout that needs a tap, and an Apply that is
 * three writes instead of one.
 */
class BookMatchViewModelTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        class Rig(
            val repo: FakeMatchingRepository = FakeMatchingRepository(),
            val books: FakeBookRepository = FakeBookRepository(),
            val receipts: MatchReceiptStore = MatchReceiptStore(),
        ) {
            init {
                books.setBookDetail(bookDetail())
            }

            val vm by lazy { BookMatchViewModel(BOOK, repo, books, receipts, ErrorBus()) }
        }

        /** Keeps both states subscribed (WhileSubscribed) for the whole test. */
        fun TestScope.subscribe(vm: BookMatchViewModel) {
            backgroundScope.launch { vm.findState.collect {} }
            backgroundScope.launch { vm.reviewState.collect {} }
        }

        test("Find runs on open, automatically, with Your copy from Room while it searches") {
            runTest(dispatcher) {
                val rig = Rig()
                val gate = CompletableDeferred<Unit>()
                rig.repo.findGate = gate
                rig.vm.findState.test {
                    var item = awaitItem()
                    while (item.yourCopy == null) item = awaitItem()
                    val searching = item.shouldBeInstanceOf<FindUiState.Searching>()
                    searching.yourCopy?.durationMs shouldBe 58_200_000L
                    searching.yourCopy?.narrators shouldBe listOf("Ray Porter")
                    searching.query shouldBe "Project Hail Mary"
                    gate.complete(Unit)
                    awaitItem().shouldBeInstanceOf<FindUiState.Results>().strong shouldHaveSize 2
                }
                rig.repo.findRequests shouldBe listOf(BookFindRequest())
            }
        }

        test("results survive pick → back without a second search, and the picked row stays marked") {
            runTest(dispatcher) {
                val rig = Rig()
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.pick(HC_ONLY)
                advanceUntilIdle()
                rig.vm.reviewState.value
                    .shouldBeInstanceOf<ReviewUiState.Ready>()
                rig.vm.backToResults()
                advanceUntilIdle()
                rig.vm.reviewState.value shouldBe ReviewUiState.NoneChosen
                rig.vm.findState.value
                    .shouldBeInstanceOf<FindUiState.Results>()
                    .pickedKey shouldBe HC_ONLY
                rig.repo.findRequests shouldHaveSize 1
            }
        }

        test("choices persist per candidate across going back and a store change") {
            runTest(dispatcher) {
                val rig = Rig()
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.pick(BEST)
                advanceUntilIdle()
                rig.vm.chooseSource(BookField.DESCRIPTION, FieldChoice.Option("hardcover:d2"))
                rig.vm.chooseCover(ImageChoice.KeepCurrent)
                rig.vm.backToResults()
                rig.vm.chooseStoreForThisSearch(MetadataLocale("uk"))
                advanceUntilIdle()
                rig.repo.findRequests
                    .last()
                    .regionOverride shouldBe MetadataLocale("uk")
                rig.vm.pick(BEST)
                advanceUntilIdle()
                val ready =
                    rig.vm.reviewState.value
                        .shouldBeInstanceOf<ReviewUiState.Ready>()
                ready.changes.single().choice shouldBe FieldChoice.Option("hardcover:d2")
                ready.cover.choice shouldBe ImageChoice.KeepCurrent
                rig.repo.reviewRequests.last() shouldBe (BEST to MetadataLocale("uk"))
            }
        }

        test("two panes open the best Strong match on arrival; phones wait for a tap") {
            runTest(dispatcher) {
                val phone = Rig()
                subscribe(phone.vm)
                advanceUntilIdle()
                phone.vm.reviewState.value shouldBe ReviewUiState.NoneChosen

                val tablet = Rig()
                tablet.vm.useTwoPane(true)
                subscribe(tablet.vm)
                advanceUntilIdle()
                tablet.vm.reviewState.value
                    .shouldBeInstanceOf<ReviewUiState.Ready>()
                    .candidate.key shouldBe BEST
            }
        }

        test("a typed query and Search by title reach the server as asked") {
            runTest(dispatcher) {
                val rig = Rig()
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.search("hail mary weir")
                advanceUntilIdle()
                rig.vm.searchByTitle()
                advanceUntilIdle()
                rig.repo.findRequests.drop(1) shouldBe
                    listOf(
                        BookFindRequest(query = "hail mary weir"),
                        BookFindRequest(strategy = FindStrategy.TITLE_AUTHOR),
                    )
            }
        }

        test("offline is the offline failure, and Retry re-runs the same Find") {
            runTest(dispatcher) {
                val rig = Rig()
                rig.repo.findReply = { AppResult.Failure(TransportError.NetworkUnavailable()) }
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.findState.value
                    .shouldBeInstanceOf<FindUiState.Failed>()
                    .failure shouldBe FindFailure.Offline
                rig.repo.findReply = { AppResult.Success(findResult()) }
                rig.vm.retry()
                advanceUntilIdle()
                rig.vm.findState.value
                    .shouldBeInstanceOf<FindUiState.Results>()
                rig.repo.findRequests shouldHaveSize 2
            }
        }

        test("a rate limit counts down a second at a time, and Retry waits for zero") {
            runTest(dispatcher) {
                val rig = Rig()
                rig.repo.findReply = {
                    AppResult.Success(findResult(candidates = emptyList(), sources = listOf(SourceStatus.RateLimited(HARDCOVER, 3))))
                }
                subscribe(rig.vm)
                advanceTimeBy(1)
                (rig.vm.findState.value as FindUiState.Failed).failure shouldBe FindFailure.RateLimited(HARDCOVER, 3)
                rig.vm.retry()
                rig.repo.findRequests shouldHaveSize 1
                advanceTimeBy(1_000)
                (rig.vm.findState.value as FindUiState.Failed).failure shouldBe FindFailure.RateLimited(HARDCOVER, 2)
                advanceTimeBy(2_000)
                (rig.vm.findState.value as FindUiState.Failed).failure shouldBe FindFailure.RateLimited(HARDCOVER, 0)
                rig.vm.retry()
                advanceUntilIdle()
                rig.repo.findRequests shouldHaveSize 2
            }
        }

        test("Apply sends one request, hands the receipt to Book Detail and says Applied") {
            runTest(dispatcher) {
                val rig = Rig()
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.pick(BEST)
                advanceUntilIdle()
                rig.vm.setFieldTicked(BookField.TITLE, true)
                rig.vm.events.test {
                    rig.vm.apply()
                    awaitItem() shouldBe BookMatchEvent.Applied(receipt())
                }
                rig.repo.applyRequests shouldHaveSize 1
                rig.repo.applyRequests
                    .single()
                    .fields
                    .first { it.field == BookField.TITLE }
                    .choice shouldBe
                    FieldChoice.Option("audible:t")
                rig.receipts.all.value[BOOK] shouldBe receipt()
            }
        }

        test("ReviewOutdated reloads the Review, keeps the choices that survived, and says so") {
            runTest(dispatcher) {
                val rig = Rig()
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.pick(BEST)
                advanceUntilIdle()
                rig.vm.chooseSource(BookField.DESCRIPTION, FieldChoice.KeepCurrent)
                rig.vm.removeYourLabel(LabelKind.GENRES, "Space Opera")
                rig.repo.applyReply = { AppResult.Failure(MetadataError.ReviewOutdated()) }
                rig.repo.reviewReply = { AppResult.Success(review(it, revision = 8L)) }
                rig.vm.events.test {
                    rig.vm.apply()
                    awaitItem() shouldBe BookMatchEvent.ReviewReloaded
                }
                val ready =
                    rig.vm.reviewState.value
                        .shouldBeInstanceOf<ReviewUiState.Ready>()
                ready.applying shouldBe false
                ready.changes.single().choice shouldBe FieldChoice.KeepCurrent
                ready.genres.removedLabels shouldBe listOf("Space Opera")
                rig.repo.applyReply = { AppResult.Success(receipt()) }
                rig.vm.apply()
                advanceUntilIdle()
                rig.repo.applyRequests
                    .last()
                    .basedOnRevision shouldBe 8L
            }
        }

        test("any other Apply failure stays on Review, inline, with nothing handed to Book Detail") {
            runTest(dispatcher) {
                val rig = Rig()
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.pick(BEST)
                advanceUntilIdle()
                val failure = MetadataError.CoverDownloadFailed()
                rig.repo.applyReply = { AppResult.Failure(failure) }
                rig.vm.apply()
                advanceUntilIdle()
                val ready =
                    rig.vm.reviewState.value
                        .shouldBeInstanceOf<ReviewUiState.Ready>()
                ready.applyError shouldBe failure
                ready.applying shouldBe false
                rig.receipts.all.first() shouldBe emptyMap()
            }
        }

        test("a failed Review carries its typed error") {
            runTest(dispatcher) {
                val rig = Rig()
                rig.repo.reviewReply = { AppResult.Failure(MetadataError.ExternalTimeout()) }
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.pick(BEST)
                advanceUntilIdle()
                rig.vm.reviewState.value
                    .shouldBeInstanceOf<ReviewUiState.Failed>()
                    .error shouldBe MetadataError.ExternalTimeout()
            }
        }

        test("close() cancels the search in flight, so nothing lands after the screen has gone; twice is harmless") {
            runTest(dispatcher) {
                val rig = Rig()
                val gate = CompletableDeferred<Unit>()
                var answered = false
                rig.repo.findGate = gate
                rig.repo.findReply = {
                    answered = true
                    AppResult.Success(findResult())
                }
                subscribe(rig.vm)
                advanceUntilIdle()

                rig.vm.close()
                rig.vm.close()
                gate.complete(Unit)
                advanceUntilIdle()

                answered shouldBe false
            }
        }
    })
