package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.flow.map

/**
 * [SeriesDao.observeVisibleMemberships] feeds every series count the hierarchy shows — the Library's
 * "23 books", a picker row's "7 books". A held or deleted book must never be counted, and a released
 * one must be.
 */
class SeriesVisibleMembershipsTest :
    FunSpec({
        test("held and deleted books are not members a count can see; a released book is") {
            withHeldBookDb { db ->
                HeldBookFixture.seedBook(db, "visible")
                HeldBookFixture.seedBook(db, "held")
                HeldBookFixture.seedBook(db, "deleted")
                HeldBookFixture.publish(db, "visible")
                HeldBookFixture.publish(db, "deleted")
                HeldBookFixture.hold(db, "held")
                db.bookDao().softDelete(BookId("deleted"), deletedAt = 9L, revision = 9L)
                db.seriesDao().upsert(
                    SeriesEntity(
                        id = SeriesId("s1"),
                        name = "Saga",
                        description = null,
                        createdAt = Timestamp(1L),
                        updatedAt = Timestamp(1L),
                    ),
                )
                db.bookSeriesDao().insertAll(
                    listOf("visible", "held", "deleted").map {
                        BookSeriesCrossRef(bookId = BookId(it), seriesId = SeriesId("s1"), sequence = 1.0)
                    },
                )

                db.seriesDao().observeVisibleMemberships().map { rows -> rows.map { it.seriesId to it.bookId } }.test {
                    awaitItem() shouldContainExactly listOf("s1" to "visible")
                    HeldBookFixture.applyReleaseEcho(db, "held")
                    awaitItemMatching { "s1" to "held" in it } shouldContainExactlyInAnyOrder
                        listOf("s1" to "visible", "s1" to "held")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
