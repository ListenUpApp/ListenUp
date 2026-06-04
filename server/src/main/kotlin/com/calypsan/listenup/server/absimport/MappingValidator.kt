package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.UserTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Validates an admin-confirmed ABS import mapping before it is persisted or applied.
 *
 * A mapping is the admin's commitment that "these ABS users are these ListenUp users, and these
 * items map to these books." Three things can make it incoherent, and all three would silently
 * corrupt someone's library if applied — so they are rejected up front with [ImportError.MappingInvalid]:
 *
 * - a mapped [UserId] that no longer exists (or has been soft-deleted),
 * - a non-null book override pointing at a [BookId] that no longer exists,
 * - two distinct ABS users collapsed onto the same ListenUp user (one person's progress would
 *   overwrite another's).
 *
 * Returns `null` when the mapping is coherent. Lives separately from the applier so the service can
 * validate at `confirmMapping` time, before anything is written.
 */
class MappingValidator(
    private val db: Database,
) {
    /** Returns [ImportError.MappingInvalid] describing the first problem found, or null if valid. */
    suspend fun validateMapping(
        userMappings: Map<AbsUserId, UserId>,
        bookOverrides: Map<AbsItemId, BookId?>,
    ): ImportError? {
        val targets = userMappings.values
        if (targets.size != targets.toSet().size) {
            return ImportError.MappingInvalid(
                debugInfo = "Two ABS users map to the same ListenUp user.",
            )
        }

        val missingUser = firstMissingUser(targets.toSet())
        if (missingUser != null) {
            return ImportError.MappingInvalid(debugInfo = "Mapped user does not exist: ${missingUser.value}")
        }

        val overrideBooks = bookOverrides.values.filterNotNull().toSet()
        val missingBook = firstMissingBook(overrideBooks)
        if (missingBook != null) {
            return ImportError.MappingInvalid(debugInfo = "Override book does not exist: ${missingBook.value}")
        }

        return null
    }

    /** The first requested user id with no live (non-deleted) row, or null if all exist. */
    private suspend fun firstMissingUser(userIds: Set<UserId>): UserId? {
        if (userIds.isEmpty()) return null
        val wanted = userIds.map { it.value }
        val present =
            suspendTransaction(db) {
                UserTable
                    .select(UserTable.id)
                    .where { (UserTable.id inList wanted) and UserTable.deletedAt.isNull() }
                    .mapTo(mutableSetOf()) { it[UserTable.id].value }
            }
        return userIds.firstOrNull { it.value !in present }
    }

    /** The first requested book id with no live (non-deleted) row, or null if all exist. */
    private suspend fun firstMissingBook(bookIds: Set<BookId>): BookId? {
        if (bookIds.isEmpty()) return null
        val wanted = bookIds.map { it.value }
        val present =
            suspendTransaction(db) {
                BookTable
                    .select(BookTable.id)
                    .where { (BookTable.id inList wanted) and BookTable.deletedAt.isNull() }
                    .mapTo(mutableSetOf()) { it[BookTable.id] }
            }
        return bookIds.firstOrNull { it.value !in present }
    }
}
