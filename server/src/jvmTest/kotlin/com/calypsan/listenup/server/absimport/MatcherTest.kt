package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.MatchTier
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class MatcherTest :
    FunSpec({

        // --- BookMatcher tier coverage ---------------------------------------------------------

        test("ASIN-only item matches at the ASIN tier with the right book id") {
            withSeededLibrary { matcher, libId ->
                val result = matcher.match(absItem(id = "abs-asin", asin = "B00ASIN001"), libId)
                result.tier shouldBe MatchTier.ASIN
                result.bookId shouldBe BookId("b-kings")
            }
        }

        test("ISBN-only item matches at the ISBN tier") {
            withSeededLibrary { matcher, libId ->
                val result = matcher.match(absItem(id = "abs-isbn", isbn = "9780000000002"), libId)
                result.tier shouldBe MatchTier.ISBN
                result.bookId shouldBe BookId("b-mist")
            }
        }

        test("path-only item matches at the PATH tier despite case + leading-slash differences") {
            withSeededLibrary { matcher, libId ->
                // DB stores "Sanderson/The Way of Kings"; ABS gives a differently-cased, slashed path.
                val result = matcher.match(absItem(id = "abs-path", relPath = "/SANDERSON\\The Way of Kings/"), libId)
                result.tier shouldBe MatchTier.PATH
                result.bookId shouldBe BookId("b-kings")
            }
        }

        test("title+author-only item matches at the TITLE_AUTHOR tier") {
            withSeededLibrary { matcher, libId ->
                val item = absItem(id = "abs-ta", title = "The   Way of Kings!", authorName = "brandon sanderson")
                val result = matcher.match(item, libId)
                result.tier shouldBe MatchTier.TITLE_AUTHOR
                result.bookId shouldBe BookId("b-kings")
            }
        }

        test("an item matching two books is AMBIGUOUS with no book id (never auto-resolved)") {
            withSeededLibrary { matcher, libId ->
                // Two seeded books share ASIN "B00DUP" — must not pick one.
                val result = matcher.match(absItem(id = "abs-dup", asin = "B00DUP"), libId)
                result.tier shouldBe MatchTier.AMBIGUOUS
                result.bookId.shouldBeNull()
            }
        }

        test("an item matching nothing in any tier is UNMATCHED") {
            withSeededLibrary { matcher, libId ->
                val item =
                    absItem(
                        id = "abs-none",
                        title = "A Book Not In The Library",
                        asin = "B00NOPE",
                        isbn = "9789999999999",
                        relPath = "nowhere/at/all",
                        authorName = "Nobody",
                    )
                val result = matcher.match(item, libId)
                result.tier shouldBe MatchTier.UNMATCHED
                result.bookId.shouldBeNull()
            }
        }

        test("title matches but author differs falls through to UNMATCHED") {
            withSeededLibrary { matcher, libId ->
                val item = absItem(id = "abs-wrongauthor", title = "The Way of Kings", authorName = "Someone Else")
                val result = matcher.match(item, libId)
                result.tier shouldBe MatchTier.UNMATCHED
                result.bookId.shouldBeNull()
            }
        }

        test("a tombstoned book whose ASIN matches is never auto-matched (UNMATCHED)") {
            withSeededLibrary { matcher, libId ->
                // b-deleted shares no field with any live book; its ASIN is unique to it.
                val result = matcher.match(absItem(id = "abs-deleted", asin = "B00DELETED"), libId)
                result.tier shouldBe MatchTier.UNMATCHED
                result.bookId.shouldBeNull()
            }
        }

        test("a tombstoned book whose path matches is never auto-matched (UNMATCHED)") {
            withSeededLibrary { matcher, libId ->
                val result = matcher.match(absItem(id = "abs-deleted-path", relPath = "Deleted/Gone"), libId)
                result.tier shouldBe MatchTier.UNMATCHED
                result.bookId.shouldBeNull()
            }
        }

        // --- UserMatcher coverage --------------------------------------------------------------

        test("UserMatcher matches by email (case-insensitive) at STRONG with the right user id") {
            val match =
                UserMatcher().match(
                    AbsUser(id = "abs-1", username = "whatever", email = "simon@x.TEST"),
                    listOf(MatchableUser(UserId("lu-1"), "Simon@X.test", "Simon Hull")),
                )
            match.confidence shouldBe MatchTier.STRONG
            match.suggestedUserId shouldBe UserId("lu-1")
        }

        test("UserMatcher falls back to username == displayName at STRONG") {
            val match =
                UserMatcher().match(
                    AbsUser(id = "abs-2", username = "simon", email = null),
                    listOf(MatchableUser(UserId("lu-2"), "other@x.test", "Simon")),
                )
            match.confidence shouldBe MatchTier.STRONG
            match.suggestedUserId shouldBe UserId("lu-2")
        }

        test("UserMatcher with two users sharing a normalized displayName is AMBIGUOUS, no suggestion") {
            val match =
                UserMatcher().match(
                    AbsUser(id = "abs-amb-name", username = "Simon Hull", email = null),
                    listOf(
                        MatchableUser(UserId("lu-a"), "a@x.test", "simon hull"),
                        MatchableUser(UserId("lu-b"), "b@x.test", "Simon   Hull!"),
                    ),
                )
            match.confidence shouldBe MatchTier.AMBIGUOUS
            match.suggestedUserId.shouldBeNull()
        }

        test("UserMatcher with two users sharing an email is AMBIGUOUS, no suggestion") {
            val match =
                UserMatcher().match(
                    AbsUser(id = "abs-amb-email", username = "irrelevant", email = "dup@x.test"),
                    listOf(
                        MatchableUser(UserId("lu-c"), "DUP@x.test", "Name One"),
                        MatchableUser(UserId("lu-d"), "dup@x.TEST", "Name Two"),
                    ),
                )
            match.confidence shouldBe MatchTier.AMBIGUOUS
            match.suggestedUserId.shouldBeNull()
        }

        test("UserMatcher with no email or name match is UNMATCHED with a null suggestion") {
            val match =
                UserMatcher().match(
                    AbsUser(id = "abs-3", username = "stranger", email = "stranger@x.test"),
                    listOf(MatchableUser(UserId("lu-3"), "other@x.test", "Different Name")),
                )
            match.confidence shouldBe MatchTier.UNMATCHED
            match.suggestedUserId.shouldBeNull()
        }
    })

/** Runs [block] inside `runTest` against a migrated in-memory DB seeded with the matcher fixtures. */
private fun withSeededLibrary(block: suspend (matcher: BookMatcher, libId: LibraryId) -> Unit) {
    withSqlDatabase {
        val dbs = this
        runTest {
            // Seed the canonical library+folder first so currentLibrary() returns the fixed
            // "test-library" id and book inserts can satisfy the folder_id FK.
            dbs.sql.seedTestLibraryAndFolder()
            val libId = LibraryRegistry(dbs.sql).currentLibrary()
            dbs.sql.transaction { seedBooks(dbs.sql, libId.value) }
            block(BookMatcher(dbs.sql), libId)
        }
    }
}

private fun seedBooks(
    sql: ListenUpDatabase,
    libraryId: String,
) {
    insertBook(
        sql,
        libraryId,
        "b-kings",
        "The Way of Kings",
        asin = "B00ASIN001",
        isbn = "9780000000001",
        relPath = "Sanderson/The Way of Kings",
    )
    insertBook(sql, libraryId, "b-mist", "Mistborn", asin = null, isbn = "9780000000002", relPath = "Sanderson/Mistborn")
    // Two books sharing an ASIN to prove the AMBIGUOUS guard.
    insertBook(sql, libraryId, "b-dup1", "Dup One", asin = "B00DUP", isbn = null, relPath = "Dup/One")
    insertBook(sql, libraryId, "b-dup2", "Dup Two", asin = "B00DUP", isbn = null, relPath = "Dup/Two")
    // A soft-deleted book to prove tombstones are excluded from every tier.
    insertBook(
        sql,
        libraryId,
        "b-deleted",
        "Deleted Book",
        asin = "B00DELETED",
        isbn = null,
        relPath = "Deleted/Gone",
        deletedAt = 1_730_000_000_001L,
    )

    sql.contributorsQueries.insert(
        id = "c-sanderson",
        normalized_name = "brandon sanderson",
        name = "Brandon Sanderson",
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
        book_id = "b-kings",
        contributor_id = "c-sanderson",
        role = "author",
        credited_as = null,
        ordinal = 0L,
    )
}

private fun insertBook(
    sql: ListenUpDatabase,
    libraryId: String,
    bookId: String,
    title: String,
    asin: String?,
    isbn: String?,
    relPath: String,
    deletedAt: Long? = null,
) {
    val now = 1_730_000_000_000L
    sql.booksQueries.insert(
        id = bookId,
        library_id = libraryId,
        folder_id = "test-folder",
        title = title,
        sort_title = null,
        subtitle = null,
        description = null,
        publish_year = null,
        publisher = null,
        language = null,
        isbn = isbn,
        asin = asin,
        abridged = 0L,
        explicit = 0L,
        has_scan_warning = 0L,
        total_duration = 1L,
        cover_source = null,
        cover_path = null,
        cover_hash = null,
        field_provenance = "{}",
        root_rel_path = relPath,
        inode = null,
        scanned_at = now,
        revision = 1L,
        created_at = now,
        updated_at = now,
        deleted_at = deletedAt,
        client_op_id = null,
    )
}

private fun absItem(
    id: String,
    title: String = "Untitled",
    asin: String? = null,
    isbn: String? = null,
    relPath: String? = null,
    authorName: String? = null,
) = AbsItem(id = id, title = title, asin = asin, isbn = isbn, authorName = authorName, relPath = relPath)
