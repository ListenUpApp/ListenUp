package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.dto.match.PersonMatchApply
import com.calypsan.listenup.api.dto.match.PersonMatchReview
import com.calypsan.listenup.api.dto.match.PhotoReview
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.MatchingService
import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookMatchApply
import com.calypsan.listenup.api.dto.match.BookMatchReview
import com.calypsan.listenup.api.dto.match.ChapterNamesReview
import com.calypsan.listenup.api.dto.match.CoverReview
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.LabelSetChange
import com.calypsan.listenup.api.dto.match.LabelSetReview
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.dto.match.UndoResult
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.sync.Mutated
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.dto.match.InLibrary
import com.calypsan.listenup.api.dto.match.PersonFindRequest
import com.calypsan.listenup.api.dto.match.PersonFindResult
import com.calypsan.listenup.api.dto.match.PersonSearchStep
import com.calypsan.listenup.api.dto.match.SearchStep
import com.calypsan.listenup.api.dto.match.YourCopy
import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException

private val RESULT =
    BookFindResult(
        yourCopy = YourCopy(durationMs = 1_000L, narrators = emptyList(), chapterCount = null, year = null, format = null),
        steps = listOf(SearchStep.TitleAuthorLength),
        candidates = emptyList(),
        sources = emptyList(),
        region = null,
    )

private val PEOPLE =
    PersonFindResult(
        steps = listOf(PersonSearchStep.ByName("Ray Porter")),
        inLibrary = InLibrary(0, emptyList()),
        coverage = emptyList(),
        candidates = emptyList(),
        sources = emptyList(),
    )

/** A [MatchingService] with in-memory state: it remembers every request and answers from [reply]. */
private class FakeMatchingService(
    var reply: suspend () -> AppResult<BookFindResult> = { AppResult.Success(RESULT) },
    var peopleReply: suspend () -> AppResult<PersonFindResult> = { AppResult.Success(PEOPLE) },
) : MatchingService {
    val requests = mutableListOf<Pair<BookId, BookFindRequest>>()
    val peopleRequests = mutableListOf<Pair<ContributorId, PersonFindRequest>>()

    override suspend fun findPeople(
        contributorId: ContributorId,
        request: PersonFindRequest,
    ): AppResult<PersonFindResult> {
        peopleRequests += contributorId to request
        return peopleReply()
    }

    override suspend fun findBookMatches(
        bookId: BookId,
        request: BookFindRequest,
    ): AppResult<BookFindResult> {
        requests += bookId to request
        return reply()
    }

    val reviews = mutableListOf<Triple<BookId, BookCandidateKey, MetadataLocale?>>()
    val applies = mutableListOf<Pair<BookId, BookMatchApply>>()
    val undos = mutableListOf<String>()
    var applyReply: AppResult<Mutated<MatchReceipt>> = AppResult.Success(Mutated(RECEIPT))

    override suspend fun reviewBookMatch(
        bookId: BookId,
        candidate: BookCandidateKey,
        region: MetadataLocale?,
    ): AppResult<BookMatchReview> {
        reviews += Triple(bookId, candidate, region)
        return AppResult.Success(REVIEW)
    }

    override suspend fun applyBookMatch(
        bookId: BookId,
        request: BookMatchApply,
    ): AppResult<Mutated<MatchReceipt>> {
        applies += bookId to request
        return applyReply
    }

    val personReviews = mutableListOf<Triple<ContributorId, PersonCandidateKey, ContributorRole?>>()
    val personApplies = mutableListOf<Pair<ContributorId, PersonMatchApply>>()
    var personApplyReply: AppResult<Mutated<MatchReceipt>> = AppResult.Success(Mutated(RECEIPT))

    override suspend fun reviewPersonMatch(
        contributorId: ContributorId,
        candidate: PersonCandidateKey,
        role: ContributorRole?,
    ): AppResult<PersonMatchReview> {
        personReviews += Triple(contributorId, candidate, role)
        return AppResult.Success(PERSON_REVIEW)
    }

    override suspend fun applyPersonMatch(
        contributorId: ContributorId,
        request: PersonMatchApply,
    ): AppResult<Mutated<MatchReceipt>> {
        personApplies += contributorId to request
        return personApplyReply
    }

    override suspend fun undoMatch(receiptId: String): AppResult<Mutated<UndoResult>> {
        undos += receiptId
        return AppResult.Success(Mutated(UndoResult(receiptId, RECEIPT.changes)))
    }
}

private val KEY = BookCandidateKey(listOf(ExternalRef("audible", "B0X", "us")))

private val RECEIPT = MatchReceipt("r1", 5L, emptyList(), undoable = true)

private val REVIEW =
    BookMatchReview(
        candidate = KEY,
        region = null,
        basedOnRevision = 3L,
        fields = emptyList(),
        cover = CoverReview(current = null, options = emptyList(), defaultChoice = ImageChoice.KeepCurrent),
        genres = LabelSetReview(emptyList(), emptyList()),
        moods = LabelSetReview(emptyList(), emptyList()),
        chapterNames = ChapterNamesReview.Unavailable,
    )

private val PERSON_KEY = PersonCandidateKey(listOf(ExternalRef("hardcover", "250716")))

private val PERSON_REVIEW =
    PersonMatchReview(
        candidate = PERSON_KEY,
        basedOnRevision = 4L,
        photo = PhotoReview(current = null, setByHand = false, options = emptyList(), defaultChoice = ImageChoice.KeepCurrent),
        biography = null,
    )

private val PERSON_APPLY =
    PersonMatchApply(PERSON_KEY, 4L, ImageChoice.KeepCurrent, FieldChoice.KeepCurrent)

private val APPLY =
    BookMatchApply(KEY, null, 3L, emptyList(), ImageChoice.KeepCurrent, LabelSetChange(), LabelSetChange(), emptyList())

class MatchingRepositoryImplTest :
    FunSpec({
        test("Find passes the book and request through and returns the server's result") {
            val service = FakeMatchingService()
            val repository = MatchingRepositoryImpl(RpcChannel.forTest(service))

            repository.findBookMatches(BookId("b1"), BookFindRequest(query = "hail mary")) shouldBe AppResult.Success(RESULT)
            service.requests shouldBe listOf(BookId("b1") to BookFindRequest(query = "hail mary"))
        }

        test("an empty request means the automatic Find in the library's store") {
            val service = FakeMatchingService()
            MatchingRepositoryImpl(RpcChannel.forTest(service)).findBookMatches(BookId("b1"))
            service.requests.single().second shouldBe BookFindRequest()
        }

        test("a people Find passes the contributor and request through and returns the server's result") {
            val service = FakeMatchingService()
            val repository = MatchingRepositoryImpl(RpcChannel.forTest(service))
            val request = PersonFindRequest(query = "Ray Porter")

            repository.findPeople(ContributorId("c1"), request) shouldBe AppResult.Success(PEOPLE)
            service.peopleRequests shouldBe listOf(ContributorId("c1") to request)

            service.peopleReply = { AppResult.Failure(MetadataError.NotFound()) }
            repository.findPeople(ContributorId("c1"), request) shouldBe AppResult.Failure(MetadataError.NotFound())
        }

        test("Review passes the candidate and store through and returns the server's review") {
            val service = FakeMatchingService()
            val repository = MatchingRepositoryImpl(RpcChannel.forTest(service))
            repository.reviewBookMatch(BookId("b1"), KEY, MetadataLocale("uk")) shouldBe AppResult.Success(REVIEW)
            service.reviews shouldBe listOf(Triple(BookId("b1"), KEY, MetadataLocale("uk")))
        }

        test("Apply and Undo unwrap the receipt and the undo result, and pass typed failures through") {
            val service = FakeMatchingService()
            val repository = MatchingRepositoryImpl(RpcChannel.forTest(service))
            repository.applyBookMatch(BookId("b1"), APPLY) shouldBe AppResult.Success(RECEIPT)
            service.applies shouldBe listOf(BookId("b1") to APPLY)
            repository.undoMatch("r1") shouldBe AppResult.Success(UndoResult("r1", emptyList()))
            service.undos shouldBe listOf("r1")

            service.applyReply = AppResult.Failure(MetadataError.ReviewOutdated())
            repository.applyBookMatch(BookId("b1"), APPLY) shouldBe AppResult.Failure(MetadataError.ReviewOutdated())
        }

        test("a person Review names no role; Apply unwraps the receipt and passes failures") {
            val service = FakeMatchingService()
            val repository = MatchingRepositoryImpl(RpcChannel.forTest(service))
            repository.reviewPersonMatch(ContributorId("c1"), PERSON_KEY) shouldBe AppResult.Success(PERSON_REVIEW)
            service.personReviews shouldBe listOf(Triple(ContributorId("c1"), PERSON_KEY, null))

            repository.applyPersonMatch(ContributorId("c1"), PERSON_APPLY) shouldBe AppResult.Success(RECEIPT)
            service.personApplies shouldBe listOf(ContributorId("c1") to PERSON_APPLY)

            service.personApplyReply = AppResult.Failure(MetadataError.CoverDownloadFailed())
            repository.applyPersonMatch(ContributorId("c1"), PERSON_APPLY) shouldBe
                AppResult.Failure(MetadataError.CoverDownloadFailed())
        }

        test("a typed failure from the server passes through untouched") {
            val service = FakeMatchingService(reply = { AppResult.Failure(MetadataError.NotFound()) })
            MatchingRepositoryImpl(RpcChannel.forTest(service)).findBookMatches(BookId("b1")) shouldBe
                AppResult.Failure(MetadataError.NotFound())
        }

        test("a transport throw becomes a typed failure, and cancellation is re-raised") {
            val broken = FakeMatchingService(reply = { throw IllegalStateException("socket gone") })
            MatchingRepositoryImpl(RpcChannel.forTest(broken))
                .findBookMatches(BookId("b1"))
                .shouldBeInstanceOf<AppResult.Failure>()
                .error
                .shouldBeInstanceOf<UnexpectedClientError>()

            val cancelled = FakeMatchingService(reply = { throw CancellationException("left") })
            shouldThrow<CancellationException> {
                MatchingRepositoryImpl(RpcChannel.forTest(cancelled)).findBookMatches(BookId("b1"))
            }
        }
    })
