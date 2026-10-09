package com.calypsan.listenup.client.presentation.match

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.LibraryCredit
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.PersonFindRequest
import com.calypsan.listenup.api.dto.match.SourceStatus
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.RoleWithBookCount
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * [PersonMatchViewModel] — one person Match details session. What matters: Find asks for the person in no role
 * and shows what they did in your library in every role, nobody having a profile says so instead of guessing,
 * photo and biography stay separate decisions, and Apply is one request whose receipt reaches the contributor page.
 */
class PersonMatchViewModelTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        class Rig(
            val repo: FakeMatchingRepository = FakeMatchingRepository(),
            val people: FakeContributorRepository = FakeContributorRepository(),
            val receipts: MatchReceiptStore = MatchReceiptStore(),
        ) {
            val vm by lazy { PersonMatchViewModel(PERSON, repo, people, receipts, ErrorBus()) }
        }

        fun TestScope.subscribe(vm: PersonMatchViewModel) {
            backgroundScope.launch { vm.findState.collect {} }
            backgroundScope.launch { vm.reviewState.collect {} }
        }

        val strongKey = personKey()
        val maybeKey = personKey(id = "hc-ray-author")

        test("Find starts at once, asking for no role, with the header and every role's strip from Room first") {
            runTest(dispatcher) {
                val rig = Rig()
                val gate = CompletableDeferred<Unit>()
                rig.repo.findGate = gate
                subscribe(rig.vm)
                advanceUntilIdle()
                val searching =
                    rig.vm.findState.value
                        .shouldBeInstanceOf<PersonFindUiState.Searching>()
                searching.header shouldBe PersonHeaderUi("Ray Porter", null)
                searching.query shouldBe "Ray Porter"
                val strip = searching.inLibrary.shouldNotBeNull()
                strip.credits shouldBe
                    listOf(LibraryCredit(ContributorRole.NARRATOR, 5), LibraryCredit(ContributorRole.AUTHOR, 1))
                strip.bookCount shouldBe 6
                strip.titles shouldBe listOf("Narrated 1", "Narrated 2", "Narrated 3")
                strip.covers.map { it.coverPath } shouldBe listOf("covers/n-1.jpg", "covers/n-2.jpg", "covers/n-3.jpg")
                gate.complete(Unit)
                advanceUntilIdle()
                rig.vm.findState.value
                    .shouldBeInstanceOf<PersonFindUiState.Results>()
                    .strong shouldHaveSize 1
                rig.repo.personFindRequests shouldBe listOf(PersonFindRequest())
            }
        }

        test("the strip counts a book once however many roles they hold on it, and leads with their biggest role") {
            runTest(dispatcher) {
                val rig = Rig()
                rig.people.roles.value =
                    listOf(
                        RoleWithBookCount("author", 1),
                        RoleWithBookCount("translator", 2),
                        RoleWithBookCount("narrator", 1),
                    )
                rig.people.booksByRole.value =
                    mapOf(
                        "author" to listOf(libraryBook("both", "Both")),
                        "narrator" to listOf(libraryBook("both", "Both")),
                        "translator" to listOf(libraryBook("t-1", "Translated 1"), libraryBook("t-2", "Translated 2")),
                    )
                subscribe(rig.vm)
                advanceUntilIdle()
                val strip =
                    rig.vm.findState.value.inLibrary
                        .shouldNotBeNull()
                strip.credits.map { it.role } shouldBe
                    listOf(ContributorRole.TRANSLATOR, ContributorRole.AUTHOR, ContributorRole.NARRATOR)
                strip.bookCount shouldBe 3
                strip.titles shouldBe listOf("Translated 1", "Translated 2", "Both")
            }
        }

        test("a new search replaces the last; a blank one searches by their name again") {
            runTest(dispatcher) {
                val rig = Rig()
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.search("R. Porter")
                advanceUntilIdle()
                rig.vm.findState.value.query shouldBe "R. Porter"
                rig.vm.search("  ")
                advanceUntilIdle()
                rig.vm.findState.value.query shouldBe "Ray Porter"
                rig.repo.personFindRequests shouldBe
                    listOf(PersonFindRequest(), PersonFindRequest(query = "R. Porter"), PersonFindRequest())
            }
        }

        test("each row carries what they did in your library as its evidence") {
            runTest(dispatcher) {
                val rig = Rig()
                subscribe(rig.vm)
                advanceUntilIdle()
                val results =
                    rig.vm.findState.value
                        .shouldBeInstanceOf<PersonFindUiState.Results>()
                results.strong.single().libraryCredits shouldBe listOf(LibraryCredit(ContributorRole.NARRATOR, 5))
                results.maybe.single().noBooksInLibrary shouldBe true
                results.maybe.single().tier shouldBe MatchTier.MAYBE
            }
        }

        test("no profile anywhere is its own state, not a failure and not a guess") {
            runTest(dispatcher) {
                val rig = Rig()
                rig.repo.personFindReply = { AppResult.Success(personFindResult(candidates = emptyList())) }
                subscribe(rig.vm)
                advanceUntilIdle()
                val none =
                    rig.vm.findState.value
                        .shouldBeInstanceOf<PersonFindUiState.NoProfiles>()
                none.header?.name shouldBe "Ray Porter"
                none.inLibrary?.bookCount shouldBe 6
            }
        }

        test("a source that timed out with nothing found is the timeout failure, and Retry searches again") {
            runTest(dispatcher) {
                val rig = Rig()
                rig.repo.personFindReply = {
                    AppResult.Success(
                        personFindResult(
                            candidates = emptyList(),
                            sources =
                                listOf(SourceStatus.Answered(AUDIBLE, 0), SourceStatus.TimedOut(HARDCOVER)),
                        ),
                    )
                }
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.findState.value
                    .shouldBeInstanceOf<PersonFindUiState.Failed>()
                    .failure shouldBe
                    FindFailure.TimedOut(HARDCOVER)
                rig.repo.personFindReply = { AppResult.Success(personFindResult()) }
                rig.vm.retry()
                advanceUntilIdle()
                rig.vm.findState.value
                    .shouldBeInstanceOf<PersonFindUiState.Results>()
                rig.repo.personFindRequests shouldHaveSize 2
            }
        }

        test("offline is the offline failure") {
            runTest(dispatcher) {
                val rig = Rig()
                rig.repo.personFindReply = { AppResult.Failure(TransportError.NetworkUnavailable()) }
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.findState.value
                    .shouldBeInstanceOf<PersonFindUiState.Failed>()
                    .failure shouldBe FindFailure.Offline
            }
        }

        test("Review asks for the candidate alone; Back keeps the results and marks the picked row") {
            runTest(dispatcher) {
                val rig = Rig()
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.pick(maybeKey)
                advanceUntilIdle()
                rig.vm.reviewState.value
                    .shouldBeInstanceOf<PersonReviewUiState.Ready>()
                    .candidate.key shouldBe maybeKey
                rig.repo.personReviewRequests.single() shouldBe maybeKey
                rig.vm.backToResults()
                advanceUntilIdle()
                rig.vm.reviewState.value shouldBe PersonReviewUiState.NoneChosen
                rig.vm.findState.value
                    .shouldBeInstanceOf<PersonFindUiState.Results>()
                    .pickedKey shouldBe maybeKey
                rig.repo.personFindRequests shouldHaveSize 1
            }
        }

        test("two panes open the best Strong match straight away; phones wait for a tap") {
            runTest(dispatcher) {
                val phone = Rig()
                subscribe(phone.vm)
                advanceUntilIdle()
                phone.vm.reviewState.value shouldBe PersonReviewUiState.NoneChosen

                val tablet = Rig()
                tablet.vm.useTwoPane(true)
                subscribe(tablet.vm)
                advanceUntilIdle()
                tablet.vm.reviewState.value
                    .shouldBeInstanceOf<PersonReviewUiState.Ready>()
                    .candidate.key shouldBe strongKey
            }
        }

        test("photo and biography choices are separate, and survive going back and returning") {
            runTest(dispatcher) {
                val rig = Rig()
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.pick(strongKey)
                advanceUntilIdle()
                rig.vm.setPhotoTicked(false)
                advanceUntilIdle()
                var ready =
                    rig.vm.reviewState.value
                        .shouldBeInstanceOf<PersonReviewUiState.Ready>()
                ready.photo?.isTicked shouldBe false
                ready.biography?.isTicked shouldBe true
                rig.vm.backToResults()
                rig.vm.pick(strongKey)
                advanceUntilIdle()
                ready =
                    rig.vm.reviewState.value
                        .shouldBeInstanceOf<PersonReviewUiState.Ready>()
                ready.photo?.choice shouldBe ImageChoice.KeepCurrent
                rig.vm.setBiographyTicked(false)
                rig.vm.choosePhoto(ImageChoice.Candidate("p-hc"))
                advanceUntilIdle()
                ready =
                    rig.vm.reviewState.value
                        .shouldBeInstanceOf<PersonReviewUiState.Ready>()
                ready.applyBar shouldBe PersonApplySummary(photo = true, biography = false, sources = listOf(HARDCOVER))
            }
        }

        test("Apply sends one request with both choices, hands the receipt to the contributor page, says Applied") {
            runTest(dispatcher) {
                val rig = Rig()
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.pick(strongKey)
                advanceUntilIdle()
                rig.vm.chooseBiographySource(FieldChoice.KeepCurrent)
                rig.vm.events.test {
                    rig.vm.apply()
                    awaitItem() shouldBe PersonMatchEvent.Applied(personReceipt())
                }
                val request = rig.repo.personApplyRequests.single()
                request.photo shouldBe ImageChoice.Candidate("p-hc")
                request.biography shouldBe FieldChoice.KeepCurrent
                request.role shouldBe null
                request.candidate shouldBe strongKey
                rig.receipts.all.value[PERSON] shouldBe personReceipt()
            }
        }

        test("ReviewOutdated reloads the Review, keeps the choices that survived, and says so") {
            runTest(dispatcher) {
                val rig = Rig()
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.pick(strongKey)
                advanceUntilIdle()
                rig.vm.setPhotoTicked(false)
                rig.repo.personApplyReply = { AppResult.Failure(MetadataError.ReviewOutdated()) }
                rig.repo.personReviewReply = { key -> AppResult.Success(personReview(key, revision = 9L)) }
                rig.vm.events.test {
                    rig.vm.apply()
                    awaitItem() shouldBe PersonMatchEvent.ReviewReloaded
                }
                val ready =
                    rig.vm.reviewState.value
                        .shouldBeInstanceOf<PersonReviewUiState.Ready>()
                ready.photo?.isTicked shouldBe false
                ready.applying shouldBe false
                rig.repo.personApplyReply = { AppResult.Success(personReceipt()) }
                rig.vm.apply()
                advanceUntilIdle()
                rig.repo.personApplyRequests
                    .last()
                    .basedOnRevision shouldBe 9L
            }
        }

        test("any other Apply failure stays on Review, inline, with nothing handed over") {
            runTest(dispatcher) {
                val rig = Rig()
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.pick(strongKey)
                advanceUntilIdle()
                rig.repo.personApplyReply = { AppResult.Failure(MetadataError.CoverDownloadFailed()) }
                rig.vm.apply()
                advanceUntilIdle()
                val ready =
                    rig.vm.reviewState.value
                        .shouldBeInstanceOf<PersonReviewUiState.Ready>()
                ready.applyError shouldBe MetadataError.CoverDownloadFailed()
                rig.receipts.all.value shouldBe emptyMap()
            }
        }

        test("close() cancels the search in flight, so nothing lands after the screen has gone; twice is harmless") {
            runTest(dispatcher) {
                val rig = Rig()
                val gate = CompletableDeferred<Unit>()
                var answered = false
                rig.repo.findGate = gate
                rig.repo.personFindReply = {
                    answered = true
                    AppResult.Success(personFindResult())
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
