package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private const val USER = "u1"
private const val HC_BOOK = 427_578L

private fun entry(
    userBookId: Long = 1L,
    hcBookId: Long = HC_BOOK,
    title: String? = "11/22/63",
    authors: List<String> = listOf("Stephen King"),
    asin: String? = null,
    isbns: List<String> = emptyList(),
    finished: List<HardcoverFinishedRead> = listOf(HardcoverFinishedRead(7L, "2017-03-01")),
) = HardcoverShelfEntry(
    userBookId = userBookId,
    hcBookId = hcBookId,
    updatedAt = "2026-09-30T00:00:00.000001+00:00",
    finishedReads = finished,
    title = title,
    authors = authors,
    editionAsin = asin,
    editionIsbns = isbns,
    defaultAudioEditionId = 9_001L,
)

private fun SqlTestDatabases.titleAndAuthor(
    bookId: String,
    title: String,
    author: String,
) {
    driver.execute(null, "UPDATE books SET title = '${title.replace("'", "''")}' WHERE id = '$bookId'", 0)
    driver.execute(
        null,
        "INSERT OR IGNORE INTO contributors (id, normalized_name, name, revision, created_at, updated_at) " +
            "VALUES ('c-$author', '${author.lowercase()}', '$author', 0, 0, 0)",
        0,
    )
    driver.execute(
        null,
        "INSERT INTO book_contributors (book_id, contributor_id, role, ordinal) VALUES ('$bookId', 'c-$author', 'author', 0)",
        0,
    )
}

private fun resolverTest(
    role: UserRoleColumn = UserRoleColumn.ROOT,
    block: suspend SqlTestDatabases.(HardcoverShelfResolver) -> Unit,
) = withSqlDatabase {
    sql.seedTestUser(USER, userRole = role)
    sql.seedTestLibraryAndFolder()
    val resolver = HardcoverShelfResolver(sql, BookAccessPolicy(sql, driver))
    runTest { block(resolver) }
}

/** Which library book a Hardcover shelf entry is: its link, or one unambiguous local match — never a guess. */
class HardcoverShelfResolverTest :
    FunSpec({

        test("a linked book resolves through its link, even with no finished read") {
            resolverTest { resolver ->
                sql.seedTestBook("book-1")
                HardcoverBookLinkStore(sql).recordAutomaticMatch(USER, "book-1", HardcoverMatch(HC_BOOK, null, HardcoverMatchMethod.ASIN))
                resolver.resolve(USER, listOf(entry(finished = emptyList()))) shouldBe mapOf(1L to ShelfResolution.Linked("book-1"))
            }
        }

        test("an unlinked book matches by the logged edition's ASIN") {
            resolverTest { resolver ->
                sql.seedTestBook("book-1", asin = "B005UR3VFO")
                resolver.resolve(USER, listOf(entry(asin = "B005UR3VFO"))) shouldBe
                    mapOf(1L to ShelfResolution.Matched("book-1", HardcoverMatch(HC_BOOK, 9_001L, HardcoverMatchMethod.ASIN)))
            }
        }

        test("then by ISBN") {
            resolverTest { resolver ->
                sql.seedTestBook("book-1")
                driver.execute(null, "UPDATE books SET isbn = '9781451627282' WHERE id = 'book-1'", 0)
                resolver.resolve(USER, listOf(entry(isbns = listOf("9781451627282")))) shouldBe
                    mapOf(1L to ShelfResolution.Matched("book-1", HardcoverMatch(HC_BOOK, 9_001L, HardcoverMatchMethod.ISBN)))
            }
        }

        test("then by exact title and one exact author — the paperback read that ListenUp holds as an audiobook") {
            resolverTest { resolver ->
                sql.seedTestBook("book-1")
                titleAndAuthor("book-1", "11/22/63", "Stephen King")
                resolver.resolve(USER, listOf(entry())) shouldBe
                    mapOf(1L to ShelfResolution.Matched("book-1", HardcoverMatch(HC_BOOK, 9_001L, HardcoverMatchMethod.SEARCH)))
            }
        }

        test("the author tier matches any listed contributor, not only the first — Alice lists her illustrator first") {
            resolverTest { resolver ->
                sql.seedTestBook("book-1")
                titleAndAuthor("book-1", "Alice's Adventures in Wonderland", "Lewis Carroll")
                val alice = entry(title = "Alice's Adventures in Wonderland", authors = listOf("John Tenniel", "Lewis Carroll"))
                resolver.resolve(USER, listOf(alice)) shouldBe
                    mapOf(1L to ShelfResolution.Matched("book-1", HardcoverMatch(HC_BOOK, 9_001L, HardcoverMatchMethod.SEARCH)))
            }
        }

        test("a title match with no shared author is no match") {
            resolverTest { resolver ->
                sql.seedTestBook("book-1")
                titleAndAuthor("book-1", "11/22/63", "Someone Else")
                resolver.resolve(USER, listOf(entry())).shouldBeEmpty()
            }
        }

        test("two library books with the ASIN is ambiguous, and the search stops rather than guessing") {
            resolverTest { resolver ->
                sql.seedTestBook("book-1", asin = "B005UR3VFO")
                sql.seedTestBook("book-2", asin = "B005UR3VFO")
                titleAndAuthor("book-1", "11/22/63", "Stephen King")
                resolver.resolve(USER, listOf(entry(asin = "B005UR3VFO"))).shouldBeEmpty()
            }
        }

        test("a book the user left as Needs a match is never re-matched by the pull") {
            resolverTest { resolver ->
                sql.seedTestBook("book-1", asin = "B005UR3VFO")
                HardcoverBookLinkStore(sql).recordAutomaticMatch(USER, "book-1", null)
                resolver.resolve(USER, listOf(entry(asin = "B005UR3VFO"))).shouldBeEmpty()
            }
        }

        test("a book the user unlinked with Change match is never re-matched by the pull") {
            resolverTest { resolver ->
                sql.seedTestBook("book-1", asin = "B005UR3VFO")
                val links = HardcoverBookLinkStore(sql)
                links.linkManually(USER, "book-1", HC_BOOK, 9_001L)
                links.unlink(USER, "book-1")
                resolver.resolve(USER, listOf(entry(asin = "B005UR3VFO"))).shouldBeEmpty()
            }
        }

        test("a book the user can't see is never matched") {
            resolverTest(role = UserRoleColumn.MEMBER) { resolver ->
                sql.seedTestBook("book-1", asin = "B005UR3VFO")
                resolver.resolve(USER, listOf(entry(asin = "B005UR3VFO"))).shouldBeEmpty()
            }
        }

        test("an unlinked entry with no finished read isn't worth matching") {
            resolverTest { resolver ->
                sql.seedTestBook("book-1", asin = "B005UR3VFO")
                resolver.resolve(USER, listOf(entry(asin = "B005UR3VFO", finished = emptyList()))).shouldBeEmpty()
            }
        }

        test("two entries of one page never claim the same book") {
            resolverTest { resolver ->
                sql.seedTestBook("book-1", asin = "B005UR3VFO")
                val resolved =
                    resolver.resolve(
                        USER,
                        listOf(
                            entry(userBookId = 1L, hcBookId = 1L, asin = "B005UR3VFO"),
                            entry(userBookId = 2L, hcBookId = 2L, asin = "B005UR3VFO"),
                        ),
                    )
                resolved.keys shouldBe setOf(1L)
            }
        }
    })
