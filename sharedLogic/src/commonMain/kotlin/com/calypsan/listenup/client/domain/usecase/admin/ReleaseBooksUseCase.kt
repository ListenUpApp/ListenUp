package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.model.InboxReleaseResult
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Releases books from the admin inbox.
 *
 * Released books become visible to users. They are added to their
 * staged collections if any, otherwise they become publicly visible.
 */
open class ReleaseBooksUseCase(
    private val adminRepository: AdminRepository,
) {
    open suspend operator fun invoke(bookIds: List<String>): AppResult<InboxReleaseResult> =
        adminRepository.releaseBooks(bookIds)
}
