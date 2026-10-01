package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.ReceiveTurbine
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * The admin-side shape of a held book, for the held-books query specs.
 *
 * Seeds the two system collections an admin syncs (INBOX and ALL_BOOKS), books, and the memberships
 * that make a book held or public. [applyReleaseEcho] is what the server's release does to Room: the
 * INBOX membership is tombstoned at a higher revision and an ALL_BOOKS membership arrives.
 */
internal object HeldBookFixture {
    const val INBOX = "col-inbox"
    const val ALL_BOOKS = "col-all-books"

    suspend fun seedSystemCollections(db: ListenUpDatabase) {
        db.collectionDao().upsert(systemCollection(INBOX, "Inbox", isInbox = true))
        db.collectionDao().upsert(systemCollection(ALL_BOOKS, "All Books", isInbox = false))
    }

    suspend fun seedBook(
        db: ListenUpDatabase,
        id: String,
        title: String = "Book $id",
        createdAt: Long = 1_000L,
        durationMs: Long = 3_600_000L,
        coverHash: String? = null,
    ) {
        db.bookDao().upsert(
            BookEntity(
                id = BookId(id),
                libraryId = LibraryId("lib1"),
                folderId = FolderId("folder1"),
                title = title,
                coverHash = coverHash,
                totalDuration = durationMs,
                createdAt = Timestamp(createdAt),
                updatedAt = Timestamp(createdAt),
            ),
        )
    }

    fun membership(
        collectionId: String,
        bookId: String,
        createdAt: Long = 1L,
        deletedAt: Long? = null,
    ) = CollectionBookEntity(
        collectionId = collectionId,
        bookId = bookId,
        syncId = "$collectionId:$bookId",
        createdAt = createdAt,
        revision = 1L,
        deletedAt = deletedAt,
    )

    suspend fun hold(
        db: ListenUpDatabase,
        bookId: String,
        heldAt: Long = 1L,
    ) {
        db.collectionBookDao().upsert(membership(INBOX, bookId, createdAt = heldAt))
    }

    suspend fun publish(
        db: ListenUpDatabase,
        bookId: String,
    ) {
        db.collectionBookDao().upsert(membership(ALL_BOOKS, bookId))
    }

    suspend fun applyReleaseEcho(
        db: ListenUpDatabase,
        bookId: String,
    ) {
        db.collectionBookDao().tombstone(collectionId = INBOX, bookId = bookId, deletedAt = 5_000L, revision = 2L)
        publish(db, bookId)
    }

    private fun systemCollection(
        id: String,
        name: String,
        isInbox: Boolean,
    ) = CollectionEntity(
        id = id,
        libraryId = "lib1",
        ownerId = "root",
        name = name,
        isInbox = isInbox,
        isSystem = true,
        revision = 1L,
        updatedAt = 100L,
    )
}

/** Runs [block] against a fresh in-memory database, seeded with the system collections unless told not to. */
internal fun withHeldBookDb(
    seedSystemCollections: Boolean = true,
    block: suspend TestScope.(ListenUpDatabase) -> Unit,
) {
    val db = createInMemoryTestDatabase()
    try {
        runTest {
            if (seedSystemCollections) HeldBookFixture.seedSystemCollections(db)
            block(db)
        }
    } finally {
        db.close()
    }
}

/**
 * Await the first emission satisfying [predicate].
 *
 * A release echo is two writes (tombstone, then the ALL_BOOKS row), and Room may emit between them.
 * The specs assert on the state the writes converge to, not on how many frames it took.
 */
internal suspend fun <T> ReceiveTurbine<T>.awaitItemMatching(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
