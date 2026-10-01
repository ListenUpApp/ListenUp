package com.calypsan.listenup.server.hardcover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest

private fun edition(bookId: Long) =
    FakeHardcoverLibrary.Edition(bookId * 10, bookId, "Book $bookId", listOf("Author $bookId"), releaseYear = 2000 + bookId.toInt())

/** Book Detail names a linked book without asking Hardcover on every visit. */
class HardcoverCatalogCacheTest :
    FunSpec({

        test("a miss asks Hardcover once; a hit doesn't ask again") {
            runTest {
                val hardcover = FakeHardcoverLibrary().apply { addEdition(edition(7)) }
                val cache = HardcoverCatalogCache(hardcover.client(), NoWaitRateLimiter())
                cache.describe("hc_at_1", 7L)?.title shouldBe "Book 7"
                cache.describe("hc_at_1", 7L)?.releaseYear shouldBe 2007
                hardcover.operations shouldBe listOf("books_by_ids")
            }
        }

        test("books a search already fetched need no request") {
            runTest {
                val hardcover = FakeHardcoverLibrary()
                val cache = HardcoverCatalogCache(hardcover.client(), NoWaitRateLimiter())
                cache.remember(listOf(HardcoverCatalogBook(7L, "Book 7", listOf("A"), null, 0, 2007, null)))
                cache.describe("hc_at_1", 7L)?.title shouldBe "Book 7"
                hardcover.operations shouldBe emptyList()
            }
        }

        test("an outage answers null and remembers nothing, so the next visit asks again") {
            runTest {
                val hardcover = FakeHardcoverLibrary().apply { addEdition(edition(7)) }
                val cache = HardcoverCatalogCache(hardcover.client(), NoWaitRateLimiter())
                hardcover.failNext(FakeReply(HttpStatusCode.ServiceUnavailable))
                cache.describe("hc_at_1", 7L).shouldBeNull()
                cache.describe("hc_at_1", 7L)?.title shouldBe "Book 7"
            }
        }

        test("past its capacity the oldest book goes first") {
            val cache = HardcoverCatalogCache(FakeHardcoverLibrary().client(), NoWaitRateLimiter(), capacity = 2)
            cache.remember((1L..3L).map { HardcoverCatalogBook(it, "Book $it", emptyList(), null, 0, null, null) })
            cache.cached(1L).shouldBeNull()
            cache.cached(3L)?.title shouldBe "Book 3"
        }
    })
