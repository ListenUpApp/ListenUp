package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow

/**
 * Who cannot see a book, derived live from the local Room mirror.
 *
 * **Admin-only by construction, not by courtesy.** A member's device never syncs the All Books
 * membership or the user roster, so anything it computed here would be wrong, not just
 * unwanted. Both reads therefore answer "nothing" on a member's device, and switch on or off
 * live if the signed-in user's role changes.
 */
interface BookVisibilityRepository {
    /**
     * Ids of the books an admin should see marked as restricted; always empty for a member. Never
     * contains a held book — the inbox's *Held* marker owns those.
     */
    fun observeRestrictedBookIds(): Flow<Set<BookId>>

    /** [bookId]'s visibility, re-emitted on every membership, share, hold or roster change; null for a member. */
    fun observeBookVisibility(bookId: BookId): Flow<BookVisibility?>
}
