@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class BookRepositoryReadPayloadsTest :
    FunSpec({

        test("readPayloads equals per-id readPayload, child ordering included") {
            withSqlDatabase {
                val repo = newRepo()
                runTest {
                    sql.seedTestLibraryAndFolder()
                    sql.transaction {
                        listOf("b1", "b2").forEachIndexed { bi, bookId ->
                            sql.seedBook(bookId, revision = (bi + 1).toLong(), coverHash = "hash-$bookId")
                            sql.contributorsQueries.insert(
                                id = "c-$bookId-0",
                                normalized_name = "a $bookId",
                                name = "Author $bookId",
                                sort_name = "Author, $bookId",
                                revision = 0L,
                                created_at = 0L,
                                updated_at = 0L,
                                deleted_at = null,
                                client_op_id = null,
                                asin = null,
                                description = null,
                                image_path = null,
                                birth_date = null,
                                death_date = null,
                                website = null,
                            )
                            sql.contributorsQueries.insert(
                                id = "c-$bookId-1",
                                normalized_name = "n $bookId",
                                name = "Narrator $bookId",
                                sort_name = null,
                                revision = 0L,
                                created_at = 0L,
                                updated_at = 0L,
                                deleted_at = null,
                                client_op_id = null,
                                asin = null,
                                description = null,
                                image_path = null,
                                birth_date = null,
                                death_date = null,
                                website = null,
                            )
                            sql.bookContributorsQueries.insert(
                                book_id = bookId,
                                contributor_id = "c-$bookId-0",
                                role = "author",
                                credited_as = null,
                                ordinal = 0L,
                            )
                            sql.bookContributorsQueries.insert(
                                book_id = bookId,
                                contributor_id = "c-$bookId-1",
                                role = "narrator",
                                credited_as = null,
                                ordinal = 1L,
                            )
                            sql.seriesQueries.insert(
                                id = "s-$bookId",
                                normalized_name = "series $bookId",
                                name = "Series $bookId",
                                sort_name = null,
                                revision = 0L,
                                created_at = 0L,
                                updated_at = 0L,
                                deleted_at = null,
                                client_op_id = null,
                                asin = null,
                                description = null,
                                cover_path = null,
                            )
                            sql.bookSeriesMembershipsQueries.insert(
                                book_id = bookId,
                                series_id = "s-$bookId",
                                sequence = "1",
                                ordinal = 0L,
                            )
                            (0..2).forEach { ci ->
                                sql.bookChaptersQueries.insert(
                                    book_id = bookId,
                                    ordinal = ci.toLong(),
                                    id = "ch-$bookId-$ci",
                                    title = "Chapter $ci",
                                    duration = 100L,
                                    start_time = (ci * 100).toLong(),
                                )
                                sql.bookAudioFilesQueries.insert(
                                    book_id = bookId,
                                    ordinal = ci.toLong(),
                                    id = "af-$bookId-$ci",
                                    filename = "$ci.m4b",
                                    format = "m4b",
                                    codec = "aac",
                                    duration = 100L,
                                    size = 100L,
                                    codecProfile = null,
                                    spatial = null,
                                    bitrate = null,
                                    sampleRate = null,
                                    channels = null,
                                )
                            }
                            // Two live genres (distinct paths) + one soft-deleted genre — exercises the
                            // genre grouping query's orderBy(path) and its deletedAt.isNull() filter.
                            sql.seedGenre("g-$bookId-fic", name = "Fiction", slug = "fiction-$bookId", path = "fiction")
                            sql.seedGenre(
                                "g-$bookId-sf",
                                name = "Science Fiction",
                                slug = "scifi-$bookId",
                                path = "fiction/science-fiction",
                            )
                            sql.seedGenre(
                                "g-$bookId-dead",
                                name = "Deleted Genre",
                                slug = "dead-$bookId",
                                path = "deleted",
                                deletedAt = 123L,
                            )
                            sql.bookGenresQueries.insertIfAbsent(book_id = bookId, genre_id = "g-$bookId-fic")
                            sql.bookGenresQueries.insertIfAbsent(book_id = bookId, genre_id = "g-$bookId-sf")
                            sql.bookGenresQueries.insertIfAbsent(book_id = bookId, genre_id = "g-$bookId-dead")
                        }
                    }
                    val ids = listOf("b1", "b2")
                    val batched = repo.readPayloadsForTest(ids)
                    val perId = ids.mapNotNull { repo.readPayloadForTest(it) }
                    batched shouldBe perId
                }
            }
        }

        test("readPayloads returns payloads in input-id order") {
            withSqlDatabase {
                val repo = newRepo()
                runTest {
                    sql.seedTestLibraryAndFolder()
                    sql.transaction {
                        listOf("a", "b", "c").forEach { bookId -> sql.seedBook(bookId) }
                    }
                    repo.readPayloadsForTest(listOf("c", "a", "b")).map { it.id } shouldBe listOf("c", "a", "b")
                }
            }
        }

        test("readPayloads skips absent ids, keeps surrounding ones") {
            withSqlDatabase {
                val repo = newRepo()
                runTest {
                    sql.seedTestLibraryAndFolder()
                    sql.transaction {
                        listOf("x", "z").forEach { bookId -> sql.seedBook(bookId) }
                    }
                    repo.readPayloadsForTest(listOf("x", "missing", "z")).map { it.id } shouldBe listOf("x", "z")
                }
            }
        }

        test("readPayloads on empty input returns empty list") {
            withSqlDatabase {
                val repo = newRepo()
                runTest {
                    repo.readPayloadsForTest(emptyList()).shouldBeEmpty()
                }
            }
        }

        test("readPayloads returns all ids across the inList chunk boundary") {
            withSqlDatabase {
                val repo = newRepo()
                runTest {
                    sql.seedTestLibraryAndFolder()
                    val ids = (0 until 1000).map { "book-%04d".format(it) }
                    sql.transaction {
                        ids.forEach { bookId -> sql.seedBook(bookId) }
                    }
                    repo.readPayloadsForTest(ids).map { it.id } shouldBe ids
                }
            }
        }
    })

private fun SqlTestDatabases.newRepo(): BookRepository {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    return BookRepository(
        db = sql,
        driver = driver,
        bus = bus,
        registry = syncRegistry,
        contributorRepository = ContributorRepository(sql, bus, syncRegistry),
        seriesRepository = SeriesRepository(sql, bus, syncRegistry),
        genreRepository = GenreRepository(sql, bus, syncRegistry),
    )
}

/** Minimal `books` row anchored to the seeded `test-library` / `test-folder`. */
private fun ListenUpDatabase.seedBook(
    bookId: String,
    revision: Long = 1L,
    coverHash: String? = null,
) {
    booksQueries.insert(
        id = bookId,
        library_id = "test-library",
        folder_id = "test-folder",
        title = "Book $bookId",
        sort_title = "Book $bookId",
        subtitle = null,
        description = null,
        publish_year = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = 0L,
        explicit = 0L,
        has_scan_warning = 0L,
        total_duration = if (coverHash == null) 0L else 1000L,
        cover_source = coverHash?.let { "filesystem" },
        cover_path = null,
        cover_hash = coverHash,
        field_provenance = "{}",
        root_rel_path = "path/$bookId",
        inode = null,
        scanned_at = if (coverHash == null) 0L else 1L,
        revision = revision,
        created_at = if (coverHash == null) 0L else 1L,
        updated_at = if (coverHash == null) 0L else 1L,
        deleted_at = null,
        client_op_id = null,
    )
}

/** One `genres` row; mirrors the GenreTable seed used by these payload fixtures. */
private fun ListenUpDatabase.seedGenre(
    id: String,
    name: String,
    slug: String,
    path: String,
    deletedAt: Long? = null,
) {
    genresQueries.insert(
        id = id,
        name = name,
        slug = slug,
        path = path,
        parent_id = null,
        depth = 0,
        sort_order = 0,
        color = null,
        description = null,
        revision = 0L,
        created_at = 0L,
        updated_at = 0L,
        deleted_at = deletedAt,
        client_op_id = null,
    )
}
