package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.flow.map

/**
 * Every [BookDao] list an admin reaches from the library excludes held books — and shows them again
 * once the release echo lands. One test per query, so a query that loses its `NOT IN` fails by name.
 */
class BookDaoHeldExclusionTest :
    FunSpec({
        suspend fun seedVisibleAndHeld(db: ListenUpDatabase) {
            HeldBookFixture.seedBook(db, "visible", createdAt = 1_000L)
            HeldBookFixture.seedBook(db, "held", createdAt = 2_000L)
            HeldBookFixture.publish(db, "visible")
            HeldBookFixture.hold(db, "held")
        }

        test("observeAllWithContributors — the library grid") {
            withHeldBookDb { db ->
                seedVisibleAndHeld(db)
                db.bookDao().observeAllWithContributors().map { rows -> rows.map { it.book.id.value } }.test {
                    awaitItem() shouldContainExactly listOf("visible")
                    HeldBookFixture.applyReleaseEcho(db, "held")
                    awaitItemMatching { "held" in it } shouldContainExactlyInAnyOrder listOf("visible", "held")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeBySeriesIdWithContributors — series detail") {
            withHeldBookDb { db ->
                seedVisibleAndHeld(db)
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
                    listOf(
                        BookSeriesCrossRef(bookId = BookId("visible"), seriesId = SeriesId("s1"), sequence = 1.0),
                        BookSeriesCrossRef(bookId = BookId("held"), seriesId = SeriesId("s1"), sequence = 2.0),
                    ),
                )
                db.bookDao().observeBySeriesIdWithContributors("s1").map { rows -> rows.map { it.book.id.value } }.test {
                    awaitItem() shouldContainExactly listOf("visible")
                    HeldBookFixture.applyReleaseEcho(db, "held")
                    awaitItemMatching { "held" in it } shouldContainExactly listOf("visible", "held")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeByContributorAndRole — a contributor's books") {
            withHeldBookDb { db ->
                seedVisibleAndHeld(db)
                db.contributorDao().upsert(
                    ContributorEntity(
                        id = ContributorId("c1"),
                        name = "Ann Author",
                        description = null,
                        imagePath = null,
                        createdAt = Timestamp(0L),
                        updatedAt = Timestamp(0L),
                    ),
                )
                listOf("visible", "held").forEach { id ->
                    db.bookContributorDao().insert(
                        BookContributorCrossRef(bookId = BookId(id), contributorId = ContributorId("c1"), role = "author"),
                    )
                }
                db.bookDao().observeByContributorAndRole("c1", "author").map { rows -> rows.map { it.book.id.value } }.test {
                    awaitItem() shouldContainExactly listOf("visible")
                    HeldBookFixture.applyReleaseEcho(db, "held")
                    awaitItemMatching { "held" in it } shouldContainExactlyInAnyOrder listOf("visible", "held")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeRecentlyAddedWithAuthor — Discover's Recently added excludes before it limits") {
            withHeldBookDb { db ->
                // The held book is the NEWEST. With limit 1, a filter applied after the LIMIT would
                // return nothing at all; the SQL exclusion returns the next-newest visible book.
                seedVisibleAndHeld(db)
                db.bookDao().observeRecentlyAddedWithAuthor(limit = 1).map { rows -> rows.map { it.id.value } }.test {
                    awaitItem() shouldContainExactly listOf("visible")
                    HeldBookFixture.applyReleaseEcho(db, "held")
                    awaitItemMatching { it == listOf("held") } shouldContainExactly listOf("held")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeUnstartedCandidatesWithSeries — Discover's unstarted picks") {
            withHeldBookDb { db ->
                seedVisibleAndHeld(db)
                db.bookDao().observeUnstartedCandidatesWithSeries().map { rows -> rows.map { it.id.value }.distinct() }.test {
                    awaitItem() shouldContainExactly listOf("visible")
                    HeldBookFixture.applyReleaseEcho(db, "held")
                    awaitItemMatching { "held" in it } shouldContainExactlyInAnyOrder listOf("visible", "held")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
