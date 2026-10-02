package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.repository.BookVisibilityRepository
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [BookVisibilityRepository]. Defaults to a member's device: nothing restricted, every
 * book's visibility null. Set [restrictedBookIds] or call [setVisibility] to play an admin's.
 */
class FakeBookVisibilityRepository : BookVisibilityRepository {
    /** What [observeRestrictedBookIds] emits. */
    val restrictedBookIds = MutableStateFlow<Set<BookId>>(emptySet())

    private val visibilityByBook = mutableMapOf<BookId, MutableStateFlow<BookVisibility?>>()

    /** Make [observeBookVisibility] for [bookId] emit [visibility] now and to every live observer. */
    fun setVisibility(
        bookId: BookId,
        visibility: BookVisibility?,
    ) {
        flowFor(bookId).value = visibility
    }

    override fun observeRestrictedBookIds(): Flow<Set<BookId>> = restrictedBookIds

    override fun observeBookVisibility(bookId: BookId): Flow<BookVisibility?> = flowFor(bookId)

    private fun flowFor(bookId: BookId) = visibilityByBook.getOrPut(bookId) { MutableStateFlow(null) }
}
