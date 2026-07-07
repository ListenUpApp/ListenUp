package com.calypsan.listenup.client.domain.usecase.collection

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.validationError
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.LibraryRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.first

private val logger = KotlinLogging.logger {}

/**
 * Creates a new collection with the given name in the current library.
 *
 * Collections are library-scoped, but ListenUp's admin model is single-library-per-server, so the
 * library id is sourced from the first library observed via [LibraryRepository] — the same source
 * the admin collections screen uses. Validates that the name is not blank before calling the
 * repository. Admin-gated at the call site (collections are admin-managed).
 *
 * Usage:
 * ```kotlin
 * val result = createCollectionUseCase(name = "Staff Picks")
 * ```
 */
open class CreateCollectionUseCase(
    private val collectionRepository: CollectionRepository,
    private val libraryRepository: LibraryRepository,
) {
    /**
     * Create a new collection.
     *
     * @param name The collection name (required, will be trimmed).
     * @return Result containing the created collection or a failure.
     */
    open suspend operator fun invoke(name: String): AppResult<Collection> {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return validationError("Collection name is required")
        }

        val libraryId =
            libraryRepository
                .observeAll()
                .first()
                .firstOrNull()
                ?.id
                ?: return validationError("No library available")

        logger.info { "Creating collection: $trimmedName" }

        return collectionRepository.create(libraryId, trimmedName)
    }
}
