package com.calypsan.listenup.client.domain.usecase.library

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.repository.HomeRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import com.calypsan.listenup.client.core.validationError

private val logger = KotlinLogging.logger {}

/**
 * Use case for getting continue listening books.
 *
 * Encapsulates all business logic for continue listening:
 * - Fetching books with playback progress
 * - Filtering out completed books (>= 99% progress)
 * - Sorting by last played time
 * - Providing both one-shot and reactive (Flow) access
 *
 * The ViewModel becomes a thin coordinator that:
 * - Manages UI state
 * - Observes the Flow for reactive updates
 * - Delegates to this use case for business logic
 *
 * Follows the operator invoke pattern for clean call-site syntax:
 * ```kotlin
 * // One-shot fetch
 * when (val result = getContinueListeningUseCase(10)) {
 *     is Success -> displayBooks(result.data)
 *     is Failure -> showError(result.message)
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
        // Validate limit
        if (limit < 1) {
            return validationError("Limit must be at least 1")
        }

        if (limit > MAX_LIMIT) {
            return validationError("Limit cannot exceed $MAX_LIMIT")
        }

        return suspendRunCatching {
            when (val result = homeRepository.getContinueListening(limit)) {
                is Success -> {
                    logger.debug { "Fetched ${result.data.size} continue listening books" }
                    result.data
                }

                is Failure -> {
                    logger.warn { "Failed to fetch continue listening: ${result.message}" }
                    throw ContinueListeningException(
                        message = mapErrorMessage(result),
                        cause = null,
                    )
                }
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

    /**
     * Map technical errors to user-friendly messages by type, not by message-text matching.
     *
     * Body-level message convention: every [com.calypsan.listenup.api.error.AppError] subtype
     * has a constant `message`, so substring matching against `failure.message` is brittle. The
     * pre-Phase-3 implementation had a "database" branch that never fired — there's no AppError
     * subtype carrying that signal. If/when a local-storage error type is added, route it here.
     */
    private fun mapErrorMessage(failure: Failure): String =
        when (failure.error) {
            is TransportError.NetworkUnavailable, is TransportError.Timeout ->
                "Unable to connect to server. Showing local data."
            else -> failure.error.message
        }

    companion object {
        /** Default number of books to return. */
        const val DEFAULT_LIMIT = 10

        /** Maximum number of books to return. */
        const val MAX_LIMIT = 50
    }
}

/**
 * Exception thrown when fetching continue listening fails.
 */
class ContinueListeningException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
