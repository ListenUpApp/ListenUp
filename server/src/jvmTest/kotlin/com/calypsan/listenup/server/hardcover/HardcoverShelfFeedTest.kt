package com.calypsan.listenup.server.hardcover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

private const val HAIL_MARY = 427_578L
private const val DCC5 = 8_888L
private const val ALICE = 1_111L
private const val EPOCH = "1970-01-01T00:00:00+00:00"

private fun FakeHardcoverLibrary.withCatalog() =
    apply {
        addEdition(
            FakeHardcoverLibrary.Edition(
                id = 9_001L,
                bookId = HAIL_MARY,
                title = "Project Hail Mary",
                authors = listOf("Andy Weir"),
                asin = "B08G9RZBTT",
                readingFormatId = 2,
                defaultAudioEditionId = 9_001L,
            ),
        )
        addEdition(
            FakeHardcoverLibrary.Edition(
                id = 9_002L,
                bookId = DCC5,
                title = "The Butcher's Masquerade",
                authors = listOf("Matt Dinniman"),
                isbn13 = "9780593820254",
            ),
        )
        addEdition(
            FakeHardcoverLibrary.Edition(
                id = 9_003L,
                bookId = ALICE,
                title = "Alice's Adventures in Wonderland",
                authors = listOf("John Tenniel", "Lewis Carroll"),
            ),
        )
    }

private suspend fun HardcoverUserBooks.page(
    after: String = EPOCH,
    afterId: Long = 0L,
    limit: Int = 25,
): List<HardcoverShelfEntry> =
    changedSince("hc_at_1", after, afterId, limit)
        .shouldBeInstanceOf<HardcoverCall.Ok<List<HardcoverShelfEntry>>>()
        .value

/** The pull's view of the shelf: changed entries in (updated_at, id) order, with only dated, finished reads. */
class HardcoverShelfFeedTest :
    FunSpec({

        test("an entry carries its finished reads only, its logged edition's identifiers, and the book's title and authors") {
            runTest {
                val hardcover = FakeHardcoverLibrary().withCatalog()
                hardcover.seedShelf(
                    HAIL_MARY,
                    HardcoverStatus.READ,
                    "2017-01-02" to "2017-03-01",
                    "2026-05-22" to null,
                    editionId = 9_001L,
                )

                val entry = HardcoverUserBooks(hardcover.client()).page().single()

                entry.hcBookId shouldBe HAIL_MARY
                entry.finishedReads.map { it.finishedOn } shouldBe listOf("2017-03-01")
                entry.editionAsin shouldBe "B08G9RZBTT"
                entry.title shouldBe "Project Hail Mary"
                entry.authors shouldBe listOf("Andy Weir")
                entry.defaultAudioEditionId shouldBe 9_001L
                hardcover.operations shouldBe listOf("user_books_changed")
            }
        }

        test("every contributor comes through as an author, the illustrator listed first included") {
            runTest {
                val hardcover = FakeHardcoverLibrary().withCatalog()
                hardcover.seedShelf(ALICE, HardcoverStatus.READ, "2019-01-01" to "2019-02-01")
                HardcoverUserBooks(hardcover.client()).page().single().authors shouldBe
                    listOf("John Tenniel", "Lewis Carroll")
            }
        }

        test("a book marked Read with no finish date yields no finished read") {
            runTest {
                val hardcover = FakeHardcoverLibrary().withCatalog()
                hardcover.seedShelf(DCC5, HardcoverStatus.READ, null to null)
                HardcoverUserBooks(hardcover.client()).page().single().finishedReads shouldBe emptyList()
            }
        }

        test("the cursor pages by (updated_at, id): nothing after the last entry, and a tie on updated_at is not skipped") {
            runTest {
                val hardcover = FakeHardcoverLibrary().withCatalog()
                hardcover.seedShelf(HAIL_MARY, HardcoverStatus.READ, "2017-01-02" to "2017-03-01")
                hardcover.seedShelf(DCC5, HardcoverStatus.READ, "2026-08-13" to "2026-08-31")
                val same = "2026-09-30T00:00:00.500000+00:00"
                hardcover.setUpdatedAt(HAIL_MARY, same)
                hardcover.setUpdatedAt(DCC5, same)
                val books = HardcoverUserBooks(hardcover.client())

                val first = books.page(limit = 1).single()
                val second = books.page(after = first.updatedAt, afterId = first.userBookId, limit = 1).single()
                second.hcBookId shouldBe DCC5
                books.page(after = second.updatedAt, afterId = second.userBookId) shouldBe emptyList()
            }
        }

        test("a cursor with a trimmed fraction goes back verbatim, and pages in time order, not text order") {
            runTest {
                val hardcover = FakeHardcoverLibrary().withCatalog()
                hardcover.seedShelf(HAIL_MARY, HardcoverStatus.READ, "2017-01-02" to "2017-03-01")
                hardcover.seedShelf(DCC5, HardcoverStatus.READ, "2026-08-13" to "2026-08-31")
                hardcover.seedShelf(ALICE, HardcoverStatus.READ, "2019-01-01" to "2019-02-01")
                // As Hasura writes them (seen live): 19.1 s, 19.10654 s, and a whole 20 s with no fraction.
                // Their text lengths differ; Hardcover orders the instants, and the pull hands each one
                // back exactly as it came — never re-rendered, never compared locally.
                hardcover.setUpdatedAt(HAIL_MARY, "2026-09-30T18:04:20+00:00")
                hardcover.setUpdatedAt(DCC5, "2026-09-30T18:04:19.10654+00:00")
                hardcover.setUpdatedAt(ALICE, "2026-09-30T18:04:19.1+00:00")
                val books = HardcoverUserBooks(hardcover.client())

                val seen = mutableListOf<HardcoverShelfEntry>()
                var after = EPOCH
                var afterId = 0L
                while (true) {
                    val entry = books.page(after = after, afterId = afterId, limit = 1).singleOrNull() ?: break
                    seen += entry
                    after = entry.updatedAt
                    afterId = entry.userBookId
                }

                seen.map { it.hcBookId } shouldBe listOf(ALICE, DCC5, HAIL_MARY)
                seen.map { it.updatedAt } shouldBe
                    listOf("2026-09-30T18:04:19.1+00:00", "2026-09-30T18:04:19.10654+00:00", "2026-09-30T18:04:20+00:00")
                hardcover.requests
                    .filter { it.operation == "user_books_changed" }
                    .map { it.variables.getValue("after").jsonPrimitive.content } shouldBe
                    listOf(EPOCH) + seen.map { it.updatedAt }
            }
        }

        test("changing a read brings its entry back after the cursor") {
            runTest {
                val hardcover = FakeHardcoverLibrary().withCatalog()
                hardcover.seedShelf(HAIL_MARY, HardcoverStatus.READING, "2026-05-22" to null)
                val books = HardcoverUserBooks(hardcover.client())
                val seen = books.page().single()

                val readId =
                    hardcover
                        .shelfFor(HAIL_MARY)!!
                        .reads
                        .single()
                        .id
                hardcover.editRead(readId) { it.finishedAt = "2026-06-01" }

                books
                    .page(after = seen.updatedAt, afterId = seen.userBookId)
                    .single()
                    .finishedReads
                    .map { it.id } shouldBe listOf(readId)
            }
        }
    })
