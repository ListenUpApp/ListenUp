package com.calypsan.listenup.client.test

import com.calypsan.listenup.client.data.local.db.AdminUserRosterEntity
import com.calypsan.listenup.client.data.local.db.CollectionEntity
import com.calypsan.listenup.client.data.local.db.CollectionShareEntity

/** Id of the fixture All Books system collection — the same id `HeldBookFixture.ALL_BOOKS` seeds. */
internal const val ALL_BOOKS_ID = "col-all-books"

/** Id of the fixture Inbox system collection — the same id `HeldBookFixture.INBOX` seeds. */
internal const val INBOX_ID = "col-inbox"

/** A normal (admin-curated, non-system) collection. */
internal fun normalCollection(
    id: String,
    name: String,
    ownerId: String = "root",
    deletedAt: Long? = null,
) = CollectionEntity(
    id = id,
    libraryId = "lib1",
    ownerId = ownerId,
    name = name,
    isInbox = false,
    isSystem = false,
    revision = 1L,
    deletedAt = deletedAt,
    updatedAt = 1L,
)

/** The library's All Books system collection, as a value (jvmTest seeds it via `HeldBookFixture`). */
internal fun allBooksCollection() =
    CollectionEntity(
        id = ALL_BOOKS_ID,
        libraryId = "lib1",
        ownerId = "root",
        name = "All Books",
        isInbox = false,
        isSystem = true,
        revision = 1L,
        updatedAt = 1L,
    )

/** The library's Inbox system collection, as a value. */
internal fun inboxCollection() =
    CollectionEntity(
        id = INBOX_ID,
        libraryId = "lib1",
        ownerId = "root",
        name = "Inbox",
        isInbox = true,
        isSystem = true,
        revision = 1L,
        updatedAt = 1L,
    )

/** A read share of [collectionId] to [userId]. */
internal fun collectionShare(
    collectionId: String,
    userId: String,
    deletedAt: Long? = null,
) = CollectionShareEntity(
    id = "$collectionId:$userId",
    collectionId = collectionId,
    sharedWithUserId = userId,
    sharedByUserId = "root",
    permission = "read",
    revision = 1L,
    deletedAt = deletedAt,
    updatedAt = 1L,
)

/** A roster row; role/status are the server enum names, exactly as synced. */
internal fun rosterUser(
    id: String,
    displayName: String,
    role: String = "MEMBER",
    status: String = "ACTIVE",
    email: String = "$id@example.com",
    deletedAt: Long? = null,
) = AdminUserRosterEntity(
    id = id,
    email = email,
    displayName = displayName,
    role = role,
    status = status,
    canShare = false,
    accountCreatedAt = 1L,
    revision = 1L,
    deletedAt = deletedAt,
)
