package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map

/**
 * Continue Listening and every "resume the last book" path skip a book held for review — even
 * when it is the most recently played — and pick it up again once the release echo lands. The held
 * book's position row is untouched throughout.
 */
class PlaybackPositionDaoHeldExclusionTest :
    FunSpec({
        fun position(
            bookId: String,
            lastPlayedAt: Long,
        ) = PlaybackPositionEntity(
            bookId = BookId(bookId),
            positionMs = 5_000L,
            playbackSpeed = 1.0f,
            updatedAt = lastPlayedAt,
            isFinished = false,
            lastPlayedAt = lastPlayedAt,
        )

        // The held book is the MOST RECENT. With limit 1, a filter applied after the LIMIT would
        // return nothing at all; the SQL exclusion returns the next playable book.
        suspend fun seedPositions(db: ListenUpDatabase) {
            HeldBookFixture.seedBook(db, "visible")
            HeldBookFixture.seedBook(db, "held")
            HeldBookFixture.publish(db, "visible")
            HeldBookFixture.hold(db, "held")
            db.playbackPositionDao().save(position("visible", lastPlayedAt = 1_000L))
            db.playbackPositionDao().save(position("held", lastPlayedAt = 2_000L))
        }

        test("observeRecentPositions — Home's Continue Listening") {
            withHeldBookDb { db ->
                seedPositions(db)
                db
                    .playbackPositionDao()
                    .observeRecentPositions(limit = 1)
                    .map { rows -> rows.map { it.bookId.value } }
                    .test {
                        awaitItem() shouldContainExactly listOf("visible")
                        HeldBookFixture.applyReleaseEcho(db, "held")
                        awaitItemMatching { it == listOf("held") } shouldContainExactly listOf("held")
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("observeRecentPositions — a book held mid-subscription leaves Continue Listening") {
            withHeldBookDb { db ->
                HeldBookFixture.seedBook(db, "visible")
                HeldBookFixture.seedBook(db, "other")
                HeldBookFixture.publish(db, "visible")
                HeldBookFixture.publish(db, "other")
                db.playbackPositionDao().save(position("other", lastPlayedAt = 1_000L))
                db.playbackPositionDao().save(position("visible", lastPlayedAt = 2_000L))
                db
                    .playbackPositionDao()
                    .observeRecentPositions(limit = 1)
                    .map { rows -> rows.map { it.bookId.value } }
                    .test {
                        awaitItem() shouldContainExactly listOf("visible")
                        HeldBookFixture.hold(db, "visible")
                        awaitItemMatching { it == listOf("other") } shouldContainExactly listOf("other")
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("getRecentPositions — voice resume, Android Auto, iOS resume, media resumption") {
            withHeldBookDb { db ->
                seedPositions(db)
                db.playbackPositionDao().getRecentPositions(limit = 1).map { it.bookId.value } shouldContainExactly
                    listOf("visible")

                HeldBookFixture.applyReleaseEcho(db, "held")

                db.playbackPositionDao().getRecentPositions(limit = 1).map { it.bookId.value } shouldContainExactly
                    listOf("held")
            }
        }

        test("the held book's progress is kept while it waits") {
            withHeldBookDb { db ->
                seedPositions(db)
                db.playbackPositionDao().getLive(BookId("held"))?.positionMs shouldBe 5_000L
            }
        }
    })
