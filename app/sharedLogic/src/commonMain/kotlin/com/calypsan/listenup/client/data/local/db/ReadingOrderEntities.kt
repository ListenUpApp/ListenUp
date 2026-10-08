package com.calypsan.listenup.client.data.local.db

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * A reading order (#962) mirrored from the `reading_orders` sync domain: a named, ordered list of books
 * belonging to one series. Library-wide — every user's device holds every order, like series.
 *
 * @property id client-minted id (an order can be made offline).
 * @property seriesId the series it belongs to, at any level of the hierarchy.
 * @property name display name, unique per series.
 * @property createdBy the maker's user id.
 * @property revision monotonic server revision.
 * @property createdAt epoch ms the order was made.
 * @property updatedAt epoch ms of the last server-side write.
 * @property deletedAt epoch ms tombstone; null while live.
 */
@Entity(
    tableName = "reading_orders",
    indices = [
        Index(value = ["seriesId"]),
        Index(value = ["deletedAt"]),
    ],
)
internal data class ReadingOrderEntity(
    @PrimaryKey val id: String,
    val seriesId: String,
    val name: String,
    val createdBy: String,
    val revision: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/**
 * One book's place in one reading order (#962), mirrored from `reading_order_books`. The natural pair is
 * the primary key, like `book_tags`, so the server's echo of an optimistic add replaces the row in place
 * whatever wire id it carries; [syncId] is that opaque wire id (SERVER-SYNC-04) — client-minted for an
 * offline add, kept by the server — and is what tombstones (which ship with the pair blanked) match on.
 * No foreign keys: sync owns integrity.
 *
 * @property readingOrderId the order.
 * @property bookId the book.
 * @property syncId opaque wire identity; never parsed.
 * @property position place in the order, lowest first; sparse (clients number by rank).
 * @property revision monotonic server revision.
 * @property createdAt epoch ms the book was added.
 * @property deletedAt epoch ms tombstone; null while live.
 */
@Entity(
    tableName = "reading_order_books",
    primaryKeys = ["readingOrderId", "bookId"],
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["deletedAt"]),
        Index(value = ["syncId"], unique = true),
    ],
)
internal data class ReadingOrderBookEntity(
    val readingOrderId: String,
    val bookId: String,
    val syncId: String,
    val position: Int,
    val revision: Long = 0,
    val createdAt: Long,
    val deletedAt: Long? = null,
)

/**
 * The signed-in user's choice of reading order on one series (#962), mirrored from the user-scoped
 * `reading_order_follows` domain. [id] is "<userId>:<seriesId>". A tombstone means "no choice here —
 * inherit from the nearest ancestor".
 *
 * @property id "<userId>:<seriesId>".
 * @property seriesId the series the choice is made on.
 * @property choice the kind's name — `SERIES`, `PUBLICATION` or `ORDER`; stored as text so a kind a newer
 *   server adds can be read back as Series order rather than failing.
 * @property readingOrderId the followed user-made order; set iff [choice] is `ORDER`.
 * @property revision monotonic server revision.
 * @property createdAt epoch ms the choice was first made.
 * @property updatedAt epoch ms of the last write.
 * @property deletedAt epoch ms tombstone; null while live.
 */
@Entity(
    tableName = "reading_order_follows",
    indices = [
        Index(value = ["seriesId"]),
        Index(value = ["deletedAt"]),
    ],
)
internal data class ReadingOrderFollowEntity(
    @PrimaryKey val id: String,
    val seriesId: String,
    val choice: String,
    val readingOrderId: String?,
    val revision: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)
