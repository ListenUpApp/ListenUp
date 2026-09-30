package com.calypsan.listenup.server.hardcover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

private const val HC_BOOK = 427_578L
private const val HC_EDITION = 9_001L

/** Reading one book's shelf entry and writing statuses and reads, against the fake Hardcover. */
class HardcoverUserBooksTest :
    FunSpec({

        test("a book not on the user's shelf reads as null") {
            runTest {
                HardcoverUserBooks(FakeHardcoverLibrary().client()).userBookFor("hc_at_1", HC_BOOK) shouldBe HardcoverCall.Ok(null)
            }
        }

        test("a shelf entry comes back with its status and reads, oldest first, and its open read") {
            runTest {
                val hardcover = FakeHardcoverLibrary()
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READING, "2017-01-02" to "2017-02-03", "2026-05-22" to null)
                val shelf =
                    HardcoverUserBooks(hardcover.client())
                        .userBookFor("hc_at_1", HC_BOOK)
                        .shouldBeInstanceOf<HardcoverCall.Ok<HardcoverUserBook?>>()
                        .value!!
                shelf.statusId shouldBe HardcoverStatus.READING
                shelf.reads.map { it.finishedAt } shouldBe listOf("2017-02-03", null)
                shelf.openRead!!.startedAt shouldBe "2026-05-22"
            }
        }

        test("creating a shelf entry, opening a read, recording progress and finishing it round-trips") {
            runTest {
                val hardcover = FakeHardcoverLibrary()
                val books = HardcoverUserBooks(hardcover.client())

                val shelfId = books.createUserBook("hc_at_1", HC_BOOK, HC_EDITION, HardcoverStatus.READING).shouldBeInstanceOf<HardcoverCall.Ok<Long>>().value
                val readId =
                    books.openRead("hc_at_1", shelfId, LocalDate(2026, 5, 22), HC_EDITION).shouldBeInstanceOf<HardcoverCall.Ok<Long>>().value
                books.recordProgress("hc_at_1", readId, 5_400L) shouldBe HardcoverCall.Ok(Unit)
                books.finishRead("hc_at_1", readId, LocalDate(2026, 6, 1)) shouldBe HardcoverCall.Ok(Unit)
                books.setStatus("hc_at_1", shelfId, HardcoverStatus.READ) shouldBe HardcoverCall.Ok(Unit)

                val shelf = hardcover.shelfFor(HC_BOOK)!!
                shelf.editionId shouldBe HC_EDITION
                shelf.statusId shouldBe HardcoverStatus.READ
                shelf.reads.single().startedAt shouldBe "2026-05-22"
                shelf.reads.single().progressSeconds shouldBe 5_400L
                shelf.reads.single().finishedAt shouldBe "2026-06-01"
            }
        }

        test("a read opened without a start date sends none") {
            runTest {
                val hardcover = FakeHardcoverLibrary()
                val shelf = hardcover.seedShelf(HC_BOOK, HardcoverStatus.READING)
                HardcoverUserBooks(hardcover.client()).openRead("hc_at_1", shelf.id, null, null)
                hardcover.shelfFor(HC_BOOK)!!.reads.single().startedAt.shouldBeNull()
            }
        }

        test("a mutation answering with an error, not an id, is Failed") {
            runTest {
                HardcoverUserBooks(FakeHardcoverLibrary().client()).recordProgress("hc_at_1", 999L, 60L) shouldBe
                    HardcoverCall.Failed("update_user_book_read: Read not found")
            }
        }

        test("the transport's classification reaches the caller unchanged") {
            runTest {
                val hardcover = FakeHardcoverLibrary()
                hardcover.failNext(FakeReply(HttpStatusCode.TooManyRequests, headers = mapOf("Retry-After" to "30")))
                HardcoverUserBooks(hardcover.client()).userBookFor("hc_at_1", HC_BOOK) shouldBe HardcoverCall.Throttled(30_000L)
            }
        }
    })
