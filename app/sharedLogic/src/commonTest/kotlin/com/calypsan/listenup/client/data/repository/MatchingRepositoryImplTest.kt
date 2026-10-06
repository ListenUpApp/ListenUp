package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.MatchingService
import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.dto.match.InLibrary
import com.calypsan.listenup.api.dto.match.PersonFindRequest
import com.calypsan.listenup.api.dto.match.PersonFindResult
import com.calypsan.listenup.api.dto.match.PersonSearchStep
import com.calypsan.listenup.api.dto.match.SearchStep
import com.calypsan.listenup.api.dto.match.YourCopy
import com.calypsan.listenup.api.error.InternalError
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
        role = ContributorRole.NARRATOR,
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
}

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
            val request = PersonFindRequest(ContributorRole.NARRATOR, query = "Ray Porter")

            repository.findPeople(ContributorId("c1"), request) shouldBe AppResult.Success(PEOPLE)
            service.peopleRequests shouldBe listOf(ContributorId("c1") to request)

            service.peopleReply = { AppResult.Failure(MetadataError.NotFound()) }
            repository.findPeople(ContributorId("c1"), request) shouldBe AppResult.Failure(MetadataError.NotFound())
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
                .shouldBeInstanceOf<InternalError>()

            val cancelled = FakeMatchingService(reply = { throw CancellationException("left") })
            shouldThrow<CancellationException> {
                MatchingRepositoryImpl(RpcChannel.forTest(cancelled)).findBookMatches(BookId("b1"))
            }
        }
    })
