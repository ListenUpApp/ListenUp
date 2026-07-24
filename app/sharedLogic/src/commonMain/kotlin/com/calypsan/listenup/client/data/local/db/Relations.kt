package com.calypsan.listenup.client.data.local.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.Relation
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId

/**
 * Cross-reference entity for the many-to-many relationship between Books and Contributors.
 *
 * A contributor (e.g., Stephen King) can be associated with multiple books,
 * and a book can have multiple contributors (e.g., Author + Narrator).
 *
 * The `creditedAs` field preserves the original attribution name when an alias
 * is merged into a primary contributor. For example, when "Richard Bachman" is
 * merged into "Stephen King", books originally by Bachman keep creditedAs = "Richard Bachman".
 * This allows:
 * - Book detail page to show "by Richard Bachman" (original credit)
 * - Clicking the name navigates to Stephen King (the real contributor)
 * - Stephen King's page shows "The Running Man (as Richard Bachman)"
 *
 * @property bookId Foreign key to the book
 * @property contributorId Foreign key to the contributor
 * @property role The role of the contributor for this specific book (e.g., "author", "narrator")
 * @property creditedAs The name shown on this book (null = use contributor's name)
 */
@Entity(
    tableName = "book_contributors",
    primaryKeys = ["bookId", "contributorId", "role"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE, // If book is deleted, remove relation
        ),
        ForeignKey(
            entity = ContributorEntity::class,
            parentColumns = ["id"],
            childColumns = ["contributorId"],
            onDelete = ForeignKey.CASCADE, // If contributor is deleted, remove relation
        ),
    ],
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["contributorId"]),
    ],
)
internal data class BookContributorCrossRef(
    val bookId: BookId,
    val contributorId: ContributorId,
    // "author", "narrator", etc.
    val role: String,
    // Original attribution name (e.g., "Richard Bachman" even when linked to Stephen King)
    val creditedAs: String? = null,
)

/**
 * Cross-reference entity for the many-to-many relationship between Books and Series.
 *
 * A book can belong to multiple series (e.g., "Mistborn", "Mistborn Era 1", "The Cosmere"),
 * and a series can contain multiple books.
 *
 * @property bookId Foreign key to the book
 * @property seriesId Foreign key to the series
 * @property sequence Position in this series (e.g., "1", "1.5", "Book Zero")
 */
@Entity(
    tableName = "book_series",
    primaryKeys = ["bookId", "seriesId"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE, // If book is deleted, remove relation
        ),
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE, // If series is deleted, remove relation
        ),
    ],
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["seriesId"]),
    ],
)
internal data class BookSeriesCrossRef(
    val bookId: BookId,
    val seriesId: SeriesId,
    val sequence: String? = null,
)

/**
 * Relation POJO for loading a book with all its contributors and series in a single query.
 *
 * This eliminates the N+1 query problem by using Room's @Relation annotation
 * to batch-load all contributors and series for all books in additional queries.
 *
 * Contributors are loaded with their roles via the junction table, allowing
 * filtering by role (author, narrator, etc.) in the repository layer.
 *
 * Series are loaded with sequence info for display (e.g., "Mistborn #1").
 */
internal data class BookWithContributors(
    @Embedded val book: BookEntity,
    @Relation(
        entity = ContributorEntity::class,
        parentColumn = "id",
        entityColumn = "id",
        associateBy =
            Junction(
                value = BookContributorCrossRef::class,
                parentColumn = "bookId",
                entityColumn = "contributorId",
            ),
    )
    val contributors: List<ContributorEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "bookId",
    )
    val contributorRoles: List<BookContributorCrossRef>,
    @Relation(
        entity = SeriesEntity::class,
        parentColumn = "id",
        entityColumn = "id",
        associateBy =
            Junction(
                value = BookSeriesCrossRef::class,
                parentColumn = "bookId",
                entityColumn = "seriesId",
            ),
    )
    val series: List<SeriesEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "bookId",
    )
    val seriesSequences: List<BookSeriesCrossRef>,
)

/**
 * Data class for contributor with aggregated book count.
 *
 * Used by queries that join contributors with book_contributors
 * to count how many books a contributor is associated with.
 */
internal data class ContributorWithBookCount(
    @Embedded val contributor: ContributorEntity,
    val bookCount: Int,
)

/**
 * Relation POJO for loading a series with all its books in a single query.
 *
 * Uses Room's @Relation with Junction to handle the many-to-many relationship
 * through the book_series table, avoiding N+1 query problems when displaying
 * series with cover stacks.
 */
internal data class SeriesWithBooks(
    @Embedded val series: SeriesEntity,
    @Relation(
        entity = BookEntity::class,
        parentColumn = "id",
        entityColumn = "id",
        associateBy =
            Junction(
                value = BookSeriesCrossRef::class,
                parentColumn = "seriesId",
                entityColumn = "bookId",
            ),
    )
    val books: List<BookEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "seriesId",
    )
    val bookSequences: List<BookSeriesCrossRef>,
)

/**
 * Relation POJO for loading a book with all its series in a single query.
 *
 * Uses Room's @Relation with Junction to handle the many-to-many relationship
 * through the book_series table.
 */
internal data class BookWithSeries(
    @Embedded val book: BookEntity,
    @Relation(
        entity = SeriesEntity::class,
        parentColumn = "id",
        entityColumn = "id",
        associateBy =
            Junction(
                value = BookSeriesCrossRef::class,
                parentColumn = "bookId",
                entityColumn = "seriesId",
            ),
    )
    val series: List<SeriesEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "bookId",
    )
    val seriesSequences: List<BookSeriesCrossRef>,
)

/**
 * Data class for a contributor role with book count.
 *
 * Used by queries that count books per role for a specific contributor.
 */
internal data class RoleWithBookCount(
    val role: String,
    val bookCount: Int,
)

/**
 * Sync-substrate junction entity for the many-to-many relationship between books and tags.
 *
 * A book can have multiple tags; a tag can be applied to multiple books (curator model —
 * the tag set is global, not per-user). Soft-deletes are tombstoned via [deletedAt]; the
 * sync engine applies tombstones from [BookTagSyncPayload] into this table.
 *
 * No [ForeignKey] constraints are declared — junction integrity is maintained by the sync
 * handlers, which process parent (tags, books) events before junction events during catch-up.
 * This avoids FK-constraint failures when event ordering is not guaranteed.
 *
 * @property bookId The book this tag is applied to.
 * @property tagId The tag applied to [bookId].
 * @property syncId Opaque wire sync identity (SERVER-SYNC-04, Room v2) — matched against
 *   `SyncEvent.Deleted.id`; never parsed. Client-minted for an offline-first create,
 *   server-echoed otherwise.
 * @property createdAt Epoch millis when this junction row was first created.
 * @property revision Monotonic server revision, bumped on create or soft-delete.
 * @property deletedAt Epoch ms tombstone; null when the junction row is live.
 */
@Entity(
    tableName = "book_tags",
    primaryKeys = ["bookId", "tagId"],
    indices = [
        Index(value = ["tagId"]),
        Index(value = ["deletedAt"]),
        Index(value = ["syncId"], unique = true),
    ],
)
internal data class BookTagEntity(
    val bookId: String,
    val tagId: String,
    val syncId: String,
    val createdAt: Long,
    val revision: Long = 0,
    val deletedAt: Long? = null,
)

/**
 * Junction table for book-genre many-to-many relationship.
 *
 * Genres are attached during book writes and when manually setting genres on a
 * book via GenreApi.
 */
@Entity(
    tableName = "book_genres",
    primaryKeys = ["bookId", "genreId"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GenreEntity::class,
            parentColumns = ["id"],
            childColumns = ["genreId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["genreId"]),
    ],
)
internal data class BookGenreCrossRef(
    val bookId: BookId,
    val genreId: String,
)

/**
 * Relation POJO for loading a book with all its live (non-tombstoned) tags in a single query.
 *
 * Uses Room's @Relation with Junction to handle the many-to-many relationship
 * through the book_tags table. Note: Room's Junction does not filter by [BookTagEntity.deletedAt];
 * consumers should call [TagDao.observeForBook] for tombstone-aware observation instead.
 */
internal data class BookWithTags(
    @Embedded val book: BookEntity,
    @Relation(
        entity = TagEntity::class,
        parentColumn = "id",
        entityColumn = "id",
        associateBy =
            Junction(
                value = BookTagEntity::class,
                parentColumn = "bookId",
                entityColumn = "tagId",
            ),
    )
    val tags: List<TagEntity>,
)

/**
 * Sync-substrate junction entity for the many-to-many relationship between books and moods.
 *
 * A book can have multiple moods; a mood can be applied to multiple books (curator model —
 * the mood set is global, not per-user). Soft-deletes are tombstoned via [deletedAt]; the
 * sync engine applies tombstones from [com.calypsan.listenup.api.sync.BookMoodSyncPayload]
 * into this table.
 *
 * No [ForeignKey] constraints are declared — junction integrity is maintained by the sync
 * handlers, which process parent (moods, books) events before junction events during catch-up.
 * This avoids FK-constraint failures when event ordering is not guaranteed.
 *
 * @property bookId The book this mood is applied to.
 * @property moodId The mood applied to [bookId].
 * @property syncId Opaque wire sync identity (SERVER-SYNC-04, Room v2) — matched against
 *   `SyncEvent.Deleted.id`; never parsed. Client-minted for an offline-first create,
 *   server-echoed otherwise.
 * @property createdAt Epoch millis when this junction row was first created.
 * @property revision Monotonic server revision, bumped on create or soft-delete.
 * @property deletedAt Epoch ms tombstone; null when the junction row is live.
 */
@Entity(
    tableName = "book_moods",
    primaryKeys = ["bookId", "moodId"],
    indices = [
        Index(value = ["moodId"]),
        Index(value = ["deletedAt"]),
        Index(value = ["syncId"], unique = true),
    ],
)
internal data class BookMoodEntity(
    val bookId: String,
    val moodId: String,
    val syncId: String,
    val createdAt: Long,
    val revision: Long = 0,
    val deletedAt: Long? = null,
)

/**
 * Junction entity mapping contributors to their aliases (pen names, former names).
 *
 * Replaces the legacy [ContributorEntity.aliases] comma-separated string, which
 * silently corrupted any alias containing a comma (e.g., "King, Stephen").
 *
 * Ordering semantics: aliases have no intrinsic order — DAO queries sort
 * alphabetically via `COLLATE NOCASE` for stable display across merges and
 * syncs.
 *
 * @property contributorId Foreign key to the contributor
 * @property alias Single alias name (stored as typed — dedup is case-insensitive
 *   at the repository layer)
 */
@Entity(
    tableName = "contributor_aliases",
    primaryKeys = ["contributorId", "alias"],
    foreignKeys = [
        ForeignKey(
            entity = ContributorEntity::class,
            parentColumns = ["id"],
            childColumns = ["contributorId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["contributorId"])],
)
internal data class ContributorAliasCrossRef(
    val contributorId: ContributorId,
    val alias: String,
)

/**
 * Read projection composing a [ContributorEntity] with its aliases from the
 * [ContributorAliasCrossRef] junction. Ordering is deferred to the repository
 * layer — this wrapper only composes entity + aliases.
 *
 * Room batches the alias lookup across list reads so a single query feeds
 * N entities — O(1) queries per list read, not O(n). Consumers that don't
 * need aliases (e.g., `FtsPopulator`, `BrowseTreeProvider`) keep using the
 * entity-only DAO methods.
 *
 * @property contributor The contributor entity
 * @property aliases List of alias names in database row order. Consumers that
 *   need alphabetical display should sort at the domain boundary using
 *   `sortedWith(String.CASE_INSENSITIVE_ORDER)` — Room's `@Relation` does not
 *   support `ORDER BY` on the projected child collection, so the repository is
 *   the canonical ordering seam.
 */
internal data class ContributorWithAliases(
    @Embedded val contributor: ContributorEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "contributorId",
        entity = ContributorAliasCrossRef::class,
        projection = ["alias"],
    )
    val aliases: List<String>,
)
