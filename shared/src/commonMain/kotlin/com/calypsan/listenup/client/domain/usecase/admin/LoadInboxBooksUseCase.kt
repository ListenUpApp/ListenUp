package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.domain.model.InboxBook
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Loads books in the admin inbox.
 *
 * The inbox contains newly scanned books awaiting admin review
 * before they become visible to users.
 */
open class LoadInboxBooksUseCase(
    private val adminRepository: AdminRepository,
) {
    open suspend operator fun invoke(): AppResult<List<InboxBook>> =
        adminRepository.getInboxBooks()
}
