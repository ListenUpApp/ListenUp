package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.BookRatingSyncPayload
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class BookRatingsDomainTest :
    FunSpec({
        test("an upsert mirrors the row and a Deleted frame by wire id tombstones it") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val apply = BookRatingMirrorApply(db)
                    apply.upsert(
                        BookRatingSyncPayload(
                            id = "r1",
                            bookId = "b1",
                            userId = "u1",
                            halfStars = 7,
                            note = "Loved it.",
                            ratedAt = 1L,
                            updatedAt = 2L,
                            revision = 5L,
                        ),
                    )
                    db
                        .bookRatingDao()
                        .find("b1", "u1")
                        .shouldNotBeNull()
                        .halfStars shouldBe 7

                    apply.tombstoneById("r1", deletedAt = 9L)

                    db.bookRatingDao().observeForBook("b1").first() shouldBe emptyList()
                }
            } finally {
                db.close()
            }
        }

        test("the domain is access-gated, like every book-scoped domain") {
            val db = createInMemoryTestDatabase()
            try {
                bookRatingsDomain(db).accessGate.shouldNotBeNull()
            } finally {
                db.close()
            }
        }
    })
