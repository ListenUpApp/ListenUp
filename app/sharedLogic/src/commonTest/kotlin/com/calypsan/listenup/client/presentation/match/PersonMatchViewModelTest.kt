package com.calypsan.listenup.client.presentation.match

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.PersonFindRequest
import com.calypsan.listenup.api.dto.match.RoleCoverage
import com.calypsan.listenup.api.dto.match.SourceStatus
import com.calypsan.listenup.api.dto.match.UnavailableReason
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
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
 * [PersonMatchViewModel] — one person Match details session. What matters: Find searches the right role, each
 * role keeps its own query and results, a role nobody has profiles for says so instead of guessing, photo and
 * biography stay separate decisions, and Apply is one request whose receipt reaches the contributor page.
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

        test("Find starts in the role the person has most books in, with the header and strip from Room first") {
            runTest(dispatcher) {
                val rig = Rig()
                val gate = CompletableDeferred<Unit>()
                rig.repo.findGate = gate
                subscribe(rig.vm)
                advanceUntilIdle()
                val searching =
                    rig.vm.findState.value
                        .shouldBeInstanceOf<PersonFindUiState.Searching>()
                searching.role shouldBe ContributorRole.NARRATOR
                searching.header shouldBe PersonHeaderUi("Ray Porter", null)
                searching.query shouldBe "Ray Porter"
                val strip = searching.inLibrary.shouldNotBeNull()
                strip.bookCount shouldBe 5
                strip.titles shouldBe listOf("Narrated 1", "Narrated 2", "Narrated 3")
                strip.covers.map { it.coverPath } shouldBe listOf("covers/n-1.jpg", "covers/n-2.jpg", "covers/n-3.jpg")
                gate.complete(Unit)
                advanceUntilIdle()
                rig.vm.findState.value
                    .shouldBeInstanceOf<PersonFindUiState.Results>()
                    .strong shouldHaveSize 1
                rig.repo.personFindRequests shouldBe listOf(PersonFindRequest(ContributorRole.NARRATOR))
            }
        }

        test("an author with no narrations, or a tie, starts as author") {
            runTest(dispatcher) {
                val rig = Rig()
                rig.people.booksByRole.value =
                    mapOf("author" to listOf(libraryBook("a-1", "A")), "narrator" to listOf(libraryBook("n-1", "N")))
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.findState.value.role shouldBe ContributorRole.AUTHOR
                rig.repo.personFindRequests
                    .single()
                    .role shouldBe ContributorRole.AUTHOR
            }
        }

        test("switching role searches that role, keeps a separate query, and switching back never re-searches") {
            runTest(dispatcher) {
                val rig = Rig()
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.search("R. Porter")
                advanceUntilIdle()
                rig.vm.switchRole(ContributorRole.AUTHOR)
                advanceUntilIdle()
                val author =
                    rig.vm.findState.value
                        .shouldBeInstanceOf<PersonFindUiState.Results>()
                author.role shouldBe ContributorRole.AUTHOR
                author.query shouldBe "Ray Porter"
                author.inLibrary?.bookCount shouldBe 1
                rig.vm.switchRole(ContributorRole.NARRATOR)
                advanceUntilIdle()
                val narrator =
                    rig.vm.findState.value
                        .shouldBeInstanceOf<PersonFindUiState.Results>()
                narrator.query shouldBe "R. Porter"
                rig.repo.personFindRequests shouldBe
                    listOf(
                        PersonFindRequest(ContributorRole.NARRATOR),
                        PersonFindRequest(ContributorRole.NARRATOR, "R. Porter"),
                        PersonFindRequest(ContributorRole.AUTHOR),
                    )
            }
        }

        test("the coverage note and the Different role flag reach the results") {
            runTest(dispatcher) {
                val rig = Rig()
                subscribe(rig.vm)
                advanceUntilIdle()
                val results =
                    rig.vm.findState.value
                        .shouldBeInstanceOf<PersonFindUiState.Results>()
                results.coverageNote shouldBe CoverageNote(listOf(AUDIBLE), listOf(HARDCOVER))
                results.maybe.single().isDifferentRole shouldBe true
                results.maybe.single().tier shouldBe MatchTier.MAYBE
            }
        }

        test("no profile anywhere for the role is its own state, not a failure and not a guess") {
            runTest(dispatcher) {
                val rig = Rig()
                rig.repo.personFindReply = {
                    AppResult.Success(
                        personFindResult(
                            candidates = emptyList(),
                            coverage = listOf(RoleCoverage(AUDIBLE, false), RoleCoverage(HARDCOVER, true)),
                        ),
                    )
                }
                subscribe(rig.vm)
                advanceUntilIdle()
                val none =
                    rig.vm.findState.value
                        .shouldBeInstanceOf<PersonFindUiState.NoProfiles>()
                none.role shouldBe ContributorRole.NARRATOR
                none.header?.name shouldBe "Ray Porter"
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
                                listOf(
                                    SourceStatus.Unavailable(AUDIBLE, UnavailableReason.NO_PROFILES_FOR_ROLE),
                                    SourceStatus.TimedOut(HARDCOVER),
                                ),
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

        test("Review asks for the role searched; Back keeps the results and marks the picked row") {
            runTest(dispatcher) {
                val rig = Rig()
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.pick(maybeKey)
                advanceUntilIdle()
                rig.vm.reviewState.value
                    .shouldBeInstanceOf<PersonReviewUiState.Ready>()
                    .candidate.key shouldBe maybeKey
                rig.repo.personReviewRequests.single() shouldBe (maybeKey to ContributorRole.NARRATOR)
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
                request.role shouldBe ContributorRole.NARRATOR
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
                rig.repo.personReviewReply = { key, role -> AppResult.Success(personReview(key, role = role, revision = 9L)) }
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

        test("choices are per role: the same person picked as author starts from the author Review's defaults") {
            runTest(dispatcher) {
                val rig = Rig()
                rig.repo.personFindReply = { AppResult.Success(personFindResult(role = it.role)) }
                subscribe(rig.vm)
                advanceUntilIdle()
                rig.vm.pick(strongKey)
                advanceUntilIdle()
                rig.vm.setPhotoTicked(false)
                rig.vm.switchRole(ContributorRole.AUTHOR)
                advanceUntilIdle()
                rig.vm.reviewState.value shouldBe PersonReviewUiState.NoneChosen
                rig.vm.pick(strongKey)
                advanceUntilIdle()
                rig.vm.reviewState.value
                    .shouldBeInstanceOf<PersonReviewUiState.Ready>()
                    .photo
                    ?.isTicked shouldBe true
                rig.repo.personReviewRequests.last() shouldBe (strongKey to ContributorRole.AUTHOR)
            }
        }
    })
