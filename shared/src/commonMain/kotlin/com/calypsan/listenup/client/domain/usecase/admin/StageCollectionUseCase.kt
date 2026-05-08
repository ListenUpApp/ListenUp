package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Stages a collection for an inbox book.
 *
 * When the book is released from the inbox, it will be
 * added to this collection.
 */
open class StageCollectionUseCase(
    private val adminRepository: AdminRepository,
) {
    open suspend operator fun invoke(
        bookId: String,
        collectionId: String,
    ): AppResult<Unit> = adminRepository.stageCollection(bookId, collectionId)
}
