package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * [HomeRepository.getRecentBooksOrEmpty] is the iOS-safe list accessor: it folds the
 * `AppResult` in Kotlin so Swift never awaits one across the Swift Export bridge.
 */
class HomeRepositoryRecentBooksTest :
    FunSpec({

        fun book(id: String) =
            ContinueListeningBook(
                bookId = id,
                title = "Title $id",
                authorNames = "Author",
                coverPath = null,
                progress = 0.5f,
                currentPositionMs = 5_000L,
                totalDurationMs = 10_000L,
                lastPlayedAt = "2026-10-10T10:00:00Z",
            )

        /** Records the limit it was asked for and answers with [result]. */
        class FakeHomeRepository(
            private val result: AppResult<List<ContinueListeningBook>>,
        ) : HomeRepository {
            var requestedLimit: Int? = null

            override suspend fun getContinueListening(limit: Int): AppResult<List<ContinueListeningBook>> {
                requestedLimit = limit
                return result
            }

            override fun observeContinueListening(limit: Int): Flow<List<ContinueListeningItem>> = emptyFlow()
        }

        test("getRecentBooksOrEmpty returns the books getContinueListening finds, most recent first") {
            val repository = FakeHomeRepository(AppResult.Success(listOf(book("a"), book("b"))))

            repository.getRecentBooksOrEmpty(limit = 4).map { it.bookId } shouldBe listOf("a", "b")
        }

        test("getRecentBooksOrEmpty asks for exactly the limit it was given") {
            val repository = FakeHomeRepository(AppResult.Success(emptyList()))

            repository.getRecentBooksOrEmpty(limit = 4)

            repository.requestedLimit shouldBe 4
        }

        test("getRecentBooksOrEmpty is empty, not an error, when the read fails") {
            val repository = FakeHomeRepository(AppResult.Failure(AuthError.PermissionDenied()))

            repository.getRecentBooksOrEmpty(limit = 4).shouldBeEmpty()
        }
    })
