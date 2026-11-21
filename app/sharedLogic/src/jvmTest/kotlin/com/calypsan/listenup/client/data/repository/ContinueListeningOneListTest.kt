package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeBookRepository
import com.calypsan.listenup.client.test.fake.FakeLoadedBookStore
import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
import com.calypsan.listenup.client.test.fake.FakePlayerEngine
import com.calypsan.listenup.client.test.fake.positionFor
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

private const val ONE_HOUR_MS = 3_600_000L

/** Inside the end credits of a one-hour book (the last 36 s), but never flagged finished. */
private const val IN_THE_CREDITS_MS = ONE_HOUR_MS - 10_000L

/**
 * Continue Listening is one list, read the same way by Home's shelf and by every one-shot reader (Android Auto,
 * session and navigation resume, shortcuts, voice, Siri, the mini player's first-run seed): the same rows, the same
 * order, the same [com.calypsan.listenup.domain.BookProgress] rule, and the limit taken only after the rule.
 */
class ContinueListeningOneListTest :
    FunSpec({
        class Rig(
            val db: ListenUpDatabase,
        ) {
            val books = FakeBookRepository()
            val home = homeRepositoryOf(bookRepository = books, playbackPositionDao = db.playbackPositionDao())

            /** A one-hour book in Room, and its position, last played at [lastPlayedAt]. */
            suspend fun listened(
                id: String,
                positionMs: Long,
                lastPlayedAt: Long,
                isFinished: Boolean = false,
                inRoom: Boolean = true,
            ) {
                if (inRoom) books.setBooks(books.currentBooks() + TestData.bookListItem(id = id, duration = ONE_HOUR_MS))
                db.playbackPositionDao().save(
                    PlaybackPositionEntity(
                        bookId = BookId(id),
                        positionMs = positionMs,
                        playbackSpeed = 1.0f,
                        updatedAt = lastPlayedAt,
                        isFinished = isFinished,
                        lastPlayedAt = lastPlayedAt,
                    ),
                )
            }

            suspend fun oneShotIds(limit: Int): List<String> =
                home
                    .getContinueListening(limit)
                    .shouldBeInstanceOf<AppResult.Success<List<ContinueListeningBook>>>()
                    .data
                    .map { it.bookId }

            suspend fun shelfIds(limit: Int): List<String> =
                home.observeContinueListening(limit).first().map { item ->
                    when (item) {
                        is ContinueListeningItem.Ready -> item.bookId
                        is ContinueListeningItem.Loading -> "loading:${item.bookId}"
                    }
                }
        }

        fun withRig(block: suspend Rig.() -> Unit) {
            val db = createInMemoryTestDatabase()
            try {
                runTest { Rig(db).block() }
            } finally {
                db.close()
            }
        }

        test("a book finished most recently gives its place to the one before it, on the shelf and everywhere else") {
            withRig {
                listened("finished", positionMs = ONE_HOUR_MS, lastPlayedAt = 2_000L, isFinished = true)
                listened("in-progress", positionMs = 600_000L, lastPlayedAt = 1_000L)

                oneShotIds(limit = 1) shouldContainExactly listOf("in-progress")
                shelfIds(limit = 1) shouldContainExactly listOf("in-progress")
            }
        }

        test("a book stopped in its end credits, unflagged, is finished, and the limit is taken after that rule") {
            withRig {
                listened("in-the-credits", positionMs = IN_THE_CREDITS_MS, lastPlayedAt = 2_000L)
                listened("in-progress", positionMs = 600_000L, lastPlayedAt = 1_000L)

                oneShotIds(limit = 1) shouldContainExactly listOf("in-progress")
                shelfIds(limit = 1) shouldContainExactly listOf("in-progress")
            }
        }

        test("a book restarted to its very start is not offered to resume anywhere") {
            withRig {
                listened("restarted", positionMs = 0L, lastPlayedAt = 2_000L)
                listened("in-progress", positionMs = 600_000L, lastPlayedAt = 1_000L)

                oneShotIds(limit = 5) shouldContainExactly listOf("in-progress")
                shelfIds(limit = 5) shouldContainExactly listOf("in-progress")
            }
        }

        test("the shelf and the one-shot readers return the same books in the same order") {
            withRig {
                listened("finished", positionMs = ONE_HOUR_MS, lastPlayedAt = 9_000L, isFinished = true)
                listened("newest", positionMs = 60_000L, lastPlayedAt = 8_000L)
                listened("in-the-credits", positionMs = IN_THE_CREDITS_MS, lastPlayedAt = 7_000L)
                listened("restarted", positionMs = 0L, lastPlayedAt = 6_000L)
                listened("middle", positionMs = 1_200_000L, lastPlayedAt = 5_000L)
                listened("syncing", positionMs = 300_000L, lastPlayedAt = 4_000L, inRoom = false)
                listened("oldest", positionMs = 2_400_000L, lastPlayedAt = 3_000L)

                val shelf = shelfIds(limit = 10)
                shelf shouldContainExactly listOf("newest", "middle", "loading:syncing", "oldest")
                oneShotIds(limit = 10) shouldContainExactly shelf.filterNot { it.startsWith("loading:") }
                oneShotIds(limit = 2) shouldContainExactly listOf("newest", "middle")
            }
        }

        test("the mini player's first-run seed skips a book finished most recently and seeds the one in progress") {
            withRig {
                listened("finished", positionMs = ONE_HOUR_MS, lastPlayedAt = 2_000L, isFinished = true)
                listened("in-progress", positionMs = 600_000L, lastPlayedAt = 1_000L)
                val positions = FakePlaybackPositionRepository()
                positions.setPosition(
                    positionFor("finished", positionMs = ONE_HOUR_MS, isFinished = true).copy(lastPlayedAtMs = 2_000L),
                )
                positions.setPosition(positionFor("in-progress", positionMs = 600_000L).copy(lastPlayedAtMs = 1_000L))
                val store = FakeLoadedBookStore(migrated = false)
                val loadedBooks =
                    LoadedBookRepositoryImpl(
                        store = store,
                        bookRepository = books,
                        positionRepository = positions,
                        homeRepository = home,
                        engine = FakePlayerEngine(),
                    )

                loadedBooks.loadedBookId() shouldBe BookId("in-progress")
                store.stored shouldBe "in-progress"
            }
        }
    })
