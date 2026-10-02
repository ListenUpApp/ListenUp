package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map

/**
 * A shelf's Room-derived aggregates — count, cover mosaic, length — ignore held books on an admin's
 * device, so a shelf card never disagrees with the shelf it opens.
 */
class ShelfDaoHeldExclusionTest :
    FunSpec({
        suspend fun seedShelf(db: ListenUpDatabase) {
            HeldBookFixture.seedBook(db, "visible", durationMs = 1_000L, coverHash = "cover-visible")
            HeldBookFixture.seedBook(db, "held", durationMs = 2_000L, coverHash = "cover-held")
            HeldBookFixture.publish(db, "visible")
            HeldBookFixture.hold(db, "held")
            db.shelfDao().upsert(
                ShelfEntity(id = "s1", name = "Reading", description = "", isPrivate = false, updatedAt = 1L, createdAt = 1L),
            )
            listOf("visible", "held").forEachIndexed { index, book ->
                db.shelfBookDao().upsert(
                    ShelfBookEntity(id = "sb-$book", shelfId = "s1", bookId = book, sortOrder = index, updatedAt = 1L, createdAt = 1L),
                )
            }
        }

        test("observeMyShelvesWithBookCount — the shelf card count") {
            withHeldBookDb { db ->
                seedShelf(db)
                db.shelfDao().observeMyShelvesWithBookCount().map { rows -> rows.single().bookCount }.test {
                    awaitItem() shouldBe 1
                    HeldBookFixture.applyReleaseEcho(db, "held")
                    awaitItemMatching { it == 2 } shouldBe 2
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeShelvesContainingBookWithBookCount — the picker still lists the shelf, counting honestly") {
            withHeldBookDb { db ->
                seedShelf(db)
                db.shelfDao().observeShelvesContainingBookWithBookCount("visible").map { rows -> rows.single().bookCount }.test {
                    awaitItem() shouldBe 1
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("coverHashesFor, bookCountFor and totalDurationMsFor — the one-shot card reads") {
            withHeldBookDb { db ->
                seedShelf(db)
                db.shelfDao().coverHashesFor("s1") shouldContainExactly listOf("cover-visible")
                db.shelfDao().bookCountFor("s1") shouldBe 1
                db.shelfDao().totalDurationMsFor("s1") shouldBe 1_000L

                HeldBookFixture.applyReleaseEcho(db, "held")

                db.shelfDao().coverHashesFor("s1") shouldContainExactly listOf("cover-visible", "cover-held")
                db.shelfDao().bookCountFor("s1") shouldBe 2
                db.shelfDao().totalDurationMsFor("s1") shouldBe 3_000L
            }
        }

        test("totalDurationMsOfBooks sums exactly the named books") {
            withHeldBookDb { db ->
                seedShelf(db)
                db.shelfDao().totalDurationMsOfBooks(listOf("held")) shouldBe 2_000L
                db.shelfDao().totalDurationMsOfBooks(listOf("visible", "held")) shouldBe 3_000L
            }
        }
    })
