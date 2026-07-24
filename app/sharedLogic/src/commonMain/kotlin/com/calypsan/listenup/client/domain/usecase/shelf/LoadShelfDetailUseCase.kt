package com.calypsan.listenup.client.domain.usecase.shelf

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.domain.model.ShelfDetail
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Loads full shelf detail including books from the server.
 *
 * Fetches shelf details via the repository and resolves local cover paths
 * for books that have cached images.
 *
 * Usage:
 * ```kotlin
 * val result = loadShelfDetailUseCase(shelfId = "shelf-123")
 * ```
 */
open class LoadShelfDetailUseCase(
    private val shelfRepository: ShelfRepository,
    private val imageRepository: ImageRepository,
) {
    /**
     * Load shelf detail with resolved cover paths.
     *
     * @param shelfId The shelf ID to load
     * @return Result containing the shelf detail or a failure
     */
    open suspend operator fun invoke(shelfId: ShelfId): AppResult<ShelfDetail> {
        logger.debug { "Loading shelf detail: $shelfId" }

        return shelfRepository.getShelfDetail(shelfId).map { shelfDetail ->
            // Resolve local cover paths for books
            val booksWithLocalCovers =
                shelfDetail.books.map { book ->
                    val bookId = book.id
                    val localCoverPath =
                        if (imageRepository.bookCoverExists(bookId)) {
                            imageRepository.getBookCoverPath(bookId)
                        } else {
                            null
                        }
                    book.copy(coverPath = localCoverPath)
                }

            shelfDetail.copy(books = booksWithLocalCovers)
        }
    }
}
