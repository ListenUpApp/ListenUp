package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
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

        // The held book is the MOST RECENT, so a reader taking the first candidate would offer a book
        // the playback choke point refuses; the SQL exclusion makes the next playable book first.
        suspend fun seedPositions(db: ListenUpDatabase) {
            HeldBookFixture.seedBook(db, "visible")
            HeldBookFixture.seedBook(db, "held")
            HeldBookFixture.publish(db, "visible")
            HeldBookFixture.hold(db, "held")
            db.playbackPositionDao().save(position("visible", lastPlayedAt = 1_000L))
            db.playbackPositionDao().save(position("held", lastPlayedAt = 2_000L))
        }

        test("observeContinueListeningCandidates — Continue Listening, on Home and everywhere else") {
            withHeldBookDb { db ->
                seedPositions(db)
                db
                    .playbackPositionDao()
                    .observeContinueListeningCandidates()
                    .map { rows -> rows.map { it.bookId.value } }
                    .test {
                        awaitItem() shouldContainExactly listOf("visible")
                        HeldBookFixture.applyReleaseEcho(db, "held")
                        awaitItemMatching { it == listOf("held", "visible") } shouldContainExactly listOf("held", "visible")
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("observeContinueListeningCandidates — a book held mid-subscription leaves Continue Listening") {
            withHeldBookDb { db ->
                HeldBookFixture.seedBook(db, "visible")
                HeldBookFixture.seedBook(db, "other")
                HeldBookFixture.publish(db, "visible")
                HeldBookFixture.publish(db, "other")
                db.playbackPositionDao().save(position("other", lastPlayedAt = 1_000L))
                db.playbackPositionDao().save(position("visible", lastPlayedAt = 2_000L))
                db
                    .playbackPositionDao()
                    .observeContinueListeningCandidates()
                    .map { rows -> rows.map { it.bookId.value } }
                    .test {
                        awaitItem() shouldContainExactly listOf("visible", "other")
                        HeldBookFixture.hold(db, "visible")
                        awaitItemMatching { it == listOf("other") } shouldContainExactly listOf("other")
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("observeContinueListeningCandidates, read once — voice resume, Android Auto, iOS resume, media resumption") {
            withHeldBookDb { db ->
                seedPositions(db)
                db
                    .playbackPositionDao()
                    .observeContinueListeningCandidates()
                    .first()
                    .map { it.bookId.value } shouldContainExactly
                    listOf("visible")

                HeldBookFixture.applyReleaseEcho(db, "held")

                db
                    .playbackPositionDao()
                    .observeContinueListeningCandidates()
                    .first()
                    .map { it.bookId.value } shouldContainExactly
                    listOf("held", "visible")
            }
        }

        test("the held book's progress is kept while it waits") {
            withHeldBookDb { db ->
                seedPositions(db)
                db.playbackPositionDao().getLive(BookId("held"))?.positionMs shouldBe 5_000L
            }
        }
    })
