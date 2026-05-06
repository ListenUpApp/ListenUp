package com.calypsan.listenup.client.domain.usecase.library

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.validationError
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.repository.HomeRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val logger = KotlinLogging.logger {}

/**
 * Use case for getting continue listening books.
 *
 * Fetches books with playback progress (filtering out completed books and sorting by
 * last-played time happens in [HomeRepository]). Surfaces the unified
 * [com.calypsan.listenup.api.error.AppError] hierarchy on failure — translation to
 * user-facing copy is the consumer's responsibility (presentation layer), not the
 * domain's.
 *
 * ```kotlin
 * // One-shot fetch
 * when (val result = getContinueListeningUseCase(10)) {
 *     is Success -> displayBooks(result.data)
 *     is Failure -> showError(userMessageFor(result.error)) // presentation translator
 * }
 *
 * // Reactive observation
 * getContinueListeningUseCase.observe(10).collect { books ->
 *     displayBooks(books)
 * }
 * ```
 */
open class GetContinueListeningUseCase(
    private val homeRepository: HomeRepository,
) {
    /**
     * Execute one-shot fetch of continue listening books.
     *
     * @param limit Maximum number of books to return
     * @return Result containing list of ContinueListeningBook on success, or an error on failure
     */
    open suspend operator fun invoke(limit: Int = DEFAULT_LIMIT): AppResult<List<ContinueListeningBook>> {
        if (limit < 1) {
            return validationError("Limit must be at least 1")
        }
        if (limit > MAX_LIMIT) {
            return validationError("Limit cannot exceed $MAX_LIMIT")
        }
        return when (val result = homeRepository.getContinueListening(limit)) {
            is Success -> {
                logger.debug { "Fetched ${result.data.size} continue listening books" }
                result
            }
            is Failure -> {
                logger.warn { "Failed to fetch continue listening: ${result.error.code}" }
                result
            }
        }
    }

    /**
     * Observe continue listening books reactively.
     *
     * Returns a Flow that emits whenever playback positions change.
     * This provides instant updates without waiting for server sync.
     *
     * @param limit Maximum number of books to return
     * @return Flow emitting list of ContinueListeningBook
     */
    open fun observe(limit: Int = DEFAULT_LIMIT): Flow<List<ContinueListeningBook>> {
        val effectiveLimit = limit.coerceIn(1, MAX_LIMIT)
        return homeRepository
            .observeContinueListening(effectiveLimit)
            .map { books ->
                logger.debug { "Continue listening updated: ${books.size} books" }
                books
            }.catch { e ->
                logger.error(e) { "Error observing continue listening" }
                emit(emptyList())
            }
    }

    /**
     * Check if there are any books to continue listening to.
     *
     * Utility function for quick checks without fetching full data.
     *
     * @return Result containing true if there are books, false otherwise
     */
    open suspend fun hasBooks(): AppResult<Boolean> =
        when (val result = invoke(limit = 1)) {
            is Success -> Success(result.data.isNotEmpty())
            is Failure -> result
        }

    companion object {
        /** Default number of books to return. */
        const val DEFAULT_LIMIT = 10

        /** Maximum number of books to return. */
        const val MAX_LIMIT = 50
    }
}
