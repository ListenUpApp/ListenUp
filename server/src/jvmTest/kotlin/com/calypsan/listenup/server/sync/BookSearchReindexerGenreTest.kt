package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.testing.asSqlDatabase

import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Integration tests for [BookSearchReindexer]'s genre population.
 *
 * Covers:
 *  - [BookSearchReindexer.reindexBook] writes live genre names into the
 *    `book_search.genres` FTS5 column (single + multiple, tombstoned exclusion).
 *  - [BookSearchReindexer.reindexAllBooksForGenre] reindexes every book linked
 *    to a given genre id.
 *  - [BookSearchReindexer.reindexAllBooksForSubtree] reindexes books linked to
 *    the prefix root or any descendant via the collision-safe
 *    `path = ? OR path LIKE ? || '/%'` predicate. The `/fic` vs `/fiction`
 *    collision case is explicitly verified.
 *
 * Modelled on [BookSearchReindexerSeriesTest] — uses a real in-memory
 * Flyway-migrated SQLite database; books, genres and junction rows are seeded
 * directly via Exposed DSL.
 */
class BookSearchReindexerGenreTest :
    FunSpec({

        fun makeReindexer(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            bookTagRepo: BookTagRepository,
            tagRepo: TagRepository,
        ) = BookSearchReindexer(bookTagRepo, tagRepo, db)

        /** Seeds a minimal book_search_map + book_search FTS row for [bookId]. */
        suspend fun seedFtsRow(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            bookId: String,
            rowid: Int,
        ) {
            suspendTransaction(db) {
                val tx = TransactionManager.current()
                tx.exec(
                    stmt = "INSERT INTO book_search_map(book_id, rowid) VALUES (?, ?)",
                    args =
                        listOf(
                            TextColumnType() to bookId,
                            IntegerColumnType() to rowid,
                        ),
                )
                tx.exec(
                    stmt =
                        "INSERT INTO book_search(rowid, title, subtitle, description, contributor_names, series_names, tags, genres) " +
                            "VALUES ($rowid, ?, '', '', '', '', '', '')",
                    args = listOf(TextColumnType() to "Test Book $bookId"),
                )
            }
        }

        /** Seeds a live genre row at the given materialized [path]. */
        fun seedGenre(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            genreId: String,
            name: String,
            slug: String,
            path: String,
            parentId: String? = null,
            depth: Int = 0,
        ) {
            val now = System.currentTimeMillis()
            transaction(db) {
                GenreTable.insert {
                    it[GenreTable.id] = genreId
                    it[GenreTable.name] = name
                    it[GenreTable.slug] = slug
                    it[GenreTable.path] = path
                    it[GenreTable.parentId] = parentId
                    it[GenreTable.depth] = depth
                    it[GenreTable.sortOrder] = 0
                    it[GenreTable.color] = null
                    it[GenreTable.description] = null
                    it[GenreTable.revision] = 1L
                    it[GenreTable.createdAt] = now
                    it[GenreTable.updatedAt] = now
                    it[GenreTable.deletedAt] = null
                    it[GenreTable.clientOpId] = null
                }
            }
        }

        /** Seeds a book_genres junction row linking [bookId] to [genreId]. */
        fun seedBookGenre(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            bookId: String,
            genreId: String,
        ) {
            transaction(db) {
                BookGenreTable.insert {
                    it[BookGenreTable.bookId] = bookId
                    it[BookGenreTable.genreId] = genreId
                }
            }
        }

        /**
         * Returns true if a MATCH on `genres` for [searchTerm] finds [rowid].
         * Column-specific MATCH so the assertion is scoped to genres only —
         * not a cross-column hit on title/tags/etc.
         */
        suspend fun ftsGenresMatch(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            rowid: Int,
            searchTerm: String,
        ): Boolean {
            val dq = '"'
            val quotedTerm = "$dq${searchTerm.replace("$dq", "$dq$dq")}$dq"
            var found = false
            suspendTransaction(db) {
                val tx = TransactionManager.current()
                tx.exec(
                    stmt = "SELECT rowid FROM book_search WHERE genres MATCH ? AND rowid = ?",
                    args =
                        listOf(
                            TextColumnType() to quotedTerm,
                            IntegerColumnType() to rowid,
                        ),
                ) { rs ->
                    found = rs.next()
                }
            }
            return found
        }

        test("reindexBook writes a single genre name into book_search.genres") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                runTest {
                    seedFtsRow(db, "book1", 1)
                    seedGenre(db, "g1", "Fantasy", "fantasy", "/fantasy")
                    seedBookGenre(db, "book1", "g1")

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val reindexer = makeReindexer(db, bookTagRepo, tagRepo)

                    reindexer.reindexBook("book1")

                    ftsGenresMatch(db, 1, "Fantasy") shouldBe true
                }
            }
        }

        test("reindexBook writes multiple genre names into book_search.genres") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                runTest {
                    seedFtsRow(db, "book1", 1)
                    seedGenre(db, "g1", "Fantasy", "fantasy", "/fantasy")
                    seedGenre(db, "g2", "Adventure", "adventure", "/adventure")
                    seedBookGenre(db, "book1", "g1")
                    seedBookGenre(db, "book1", "g2")

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val reindexer = makeReindexer(db, bookTagRepo, tagRepo)

                    reindexer.reindexBook("book1")

                    ftsGenresMatch(db, 1, "Fantasy") shouldBe true
                    ftsGenresMatch(db, 1, "Adventure") shouldBe true
                }
            }
        }

        test("reindexBook excludes tombstoned genres") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                runTest {
                    seedFtsRow(db, "book1", 1)
                    seedGenre(db, "g1", "Fantasy", "fantasy", "/fantasy")
                    seedBookGenre(db, "book1", "g1")

                    // Tombstone the genre — the JOIN's deleted_at IS NULL clause excludes it.
                    val now = System.currentTimeMillis()
                    transaction(db) {
                        GenreTable.update({ GenreTable.id eq "g1" }) {
                            it[GenreTable.deletedAt] = now
                        }
                    }

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val reindexer = makeReindexer(db, bookTagRepo, tagRepo)

                    reindexer.reindexBook("book1")

                    ftsGenresMatch(db, 1, "Fantasy") shouldBe false
                }
            }
        }

        test("reindexAllBooksForGenre reindexes every book linked to the genre") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                seedTestBook("book2")
                runTest {
                    seedFtsRow(db, "book1", 1)
                    seedFtsRow(db, "book2", 2)
                    seedGenre(db, "g1", "Fantasy", "fantasy", "/fantasy")
                    seedBookGenre(db, "book1", "g1")
                    seedBookGenre(db, "book2", "g1")

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val reindexer = makeReindexer(db, bookTagRepo, tagRepo)

                    reindexer.reindexAllBooksForGenre("g1")

                    ftsGenresMatch(db, 1, "Fantasy") shouldBe true
                    ftsGenresMatch(db, 2, "Fantasy") shouldBe true
                }
            }
        }

        test("reindexAllBooksForSubtree covers descendants via path-prefix join") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                seedTestBook("book2")
                seedTestBook("book3")
                runTest {
                    seedFtsRow(db, "book1", 1)
                    seedFtsRow(db, "book2", 2)
                    seedFtsRow(db, "book3", 3)

                    // Subtree:
                    //   /fiction          ← book1
                    //   /fiction/fantasy  ← book2
                    //   /fiction/sci-fi   ← book3
                    seedGenre(db, "g1", "Fiction", "fiction", "/fiction", depth = 1)
                    seedGenre(
                        db,
                        "g2",
                        "Fantasy",
                        "fantasy",
                        "/fiction/fantasy",
                        parentId = "g1",
                        depth = 2,
                    )
                    seedGenre(
                        db,
                        "g3",
                        "Sci-Fi",
                        "sci-fi",
                        "/fiction/sci-fi",
                        parentId = "g1",
                        depth = 2,
                    )
                    seedBookGenre(db, "book1", "g1")
                    seedBookGenre(db, "book2", "g2")
                    seedBookGenre(db, "book3", "g3")

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val reindexer = makeReindexer(db, bookTagRepo, tagRepo)

                    reindexer.reindexAllBooksForSubtree("/fiction")

                    ftsGenresMatch(db, 1, "Fiction") shouldBe true
                    ftsGenresMatch(db, 2, "Fantasy") shouldBe true
                    ftsGenresMatch(db, 3, "Sci-Fi") shouldBe true
                }
            }
        }

        test("reindexAllBooksForSubtree does not touch /fic when prefix is /fiction") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("bookFic")
                seedTestBook("bookFiction")
                runTest {
                    seedFtsRow(db, "bookFic", 1)
                    seedFtsRow(db, "bookFiction", 2)

                    // Two sibling roots whose paths share a leading prefix only —
                    // the collision case `/fic` vs `/fiction`. The reindex must
                    // touch only the `/fiction` book, never the `/fic` book.
                    seedGenre(db, "g-fic", "Fic Genre", "fic", "/fic", depth = 1)
                    seedGenre(db, "g-fiction", "Fiction", "fiction", "/fiction", depth = 1)
                    seedBookGenre(db, "bookFic", "g-fic")
                    seedBookGenre(db, "bookFiction", "g-fiction")

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val reindexer = makeReindexer(db, bookTagRepo, tagRepo)

                    reindexer.reindexAllBooksForSubtree("/fiction")

                    // The /fiction book was reindexed — its genres column now has "Fiction".
                    ftsGenresMatch(db, 2, "Fiction") shouldBe true
                    // The /fic book was NOT reindexed — its genres column stays empty;
                    // "Fic Genre" never made it into the index.
                    ftsGenresMatch(db, 1, "Fic") shouldBe false
                    ftsGenresMatch(db, 1, "Fic Genre") shouldBe false
                }
            }
        }
    })
