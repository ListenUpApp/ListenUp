package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

private const val USER = "u1"
private const val T0 = 1_779_451_200_000L
private const val CURSOR = "2026-09-30T00:00:00.000009+00:00"

private class StoreRig(
    val sql: ListenUpDatabase,
) {
    val clock = MutableClock(Instant.fromEpochMilliseconds(T0))
    val store = HardcoverPullStore(sql, clock)
    val links = HardcoverBookLinkStore(sql, clock)
    val connections = HardcoverConnectionStore(sql, HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("secret")), clock)

    init {
        sql.seedTestUser(USER)
        sql.seedTestLibraryAndFolder()
        sql.seedTestBook("book-1")
        sql.seedTestBook("book-2")
    }

    suspend fun connect(hcUserId: Long = 42) =
        connections.save(USER, HardcoverMe(hcUserId, "reader"), HardcoverTokens("at", "rt", 604_800, HARDCOVER_SCOPES))

    fun listenedRead(bookId: String) =
        sql.bookReadsQueries.insert(
            id = "lu-$bookId",
            user_id = USER,
            book_id = bookId,
            finished_at = 1L,
            source = "playback",
            created_at = 1L,
        )

    fun sources(): List<Pair<String, String>> =
        sql.bookReadsQueries
            .finishesForBook("book-1")
            .executeAsList()
            .map { it.id to it.source } +
            sql.bookReadsQueries
                .finishesForBook("book-2")
                .executeAsList()
                .map { it.id to it.source }
}

private fun storeTest(block: suspend StoreRig.() -> Unit) = withSqlDatabase { runTest { StoreRig(sql).block() } }

private fun book(
    bookId: String,
    vararg reads: Pair<Long, Long>,
    newLink: HardcoverMatch? = null,
) = PulledBook(bookId, reads.map { (id, at) -> PulledRead(id, at) }, newLink)

/** The pull's persistence: one transaction per page, idempotent rows, and deletions only of pulled rows. */
class HardcoverPullStoreTest :
    FunSpec({

        test("a committed page writes pulled rows, records a new link, marks what it saw, and moves the cursor") {
            storeTest {
                connect()
                val match = HardcoverMatch(427_578L, 9_001L, HardcoverMatchMethod.ASIN)
                store.commitPage(USER, listOf(book("book-1", 7L to 100L, newLink = match)), CURSOR, cursorId = 9L, seenAt = T0)

                store.pulledReads(USER) shouldBe listOf(PulledReadRow("book-1", 100L, 7L))
                links.linkFor(USER, "book-1")!!.let {
                    it.hcBookId shouldBe 427_578L
                    it.method shouldBe HardcoverMatchMethod.ASIN
                }
                store.pullState(USER) shouldBe HardcoverPullState(CURSOR, 9L, fullPullStartedAt = null, lastFullPullAt = null)
            }
        }

        test("committing the same page twice changes nothing") {
            storeTest {
                connect()
                repeat(2) { store.commitPage(USER, listOf(book("book-1", 7L to 100L, 8L to 200L)), CURSOR, 9L, T0) }
                store.pulledReads(USER) shouldBe listOf(PulledReadRow("book-1", 100L, 7L), PulledReadRow("book-1", 200L, 8L))
            }
        }

        test("a read that left the entry is deleted, a changed date is updated, and ListenUp's own read is never touched") {
            storeTest {
                connect()
                listenedRead("book-1")
                store.commitPage(USER, listOf(book("book-1", 7L to 100L, 8L to 200L)), CURSOR, 9L, T0)
                store.commitPage(USER, listOf(book("book-1", 8L to 250L)), CURSOR, 9L, T0)
                store.pulledReads(USER) shouldBe listOf(PulledReadRow("book-1", 250L, 8L))

                store.commitPage(USER, listOf(book("book-1")), CURSOR, 9L, T0)
                store.pulledReads(USER) shouldBe emptyList()
                sources() shouldBe listOf("lu-book-1" to "playback")
            }
        }

        test("a completed full pull deletes pulled reads of entries it never saw, and only those") {
            storeTest {
                connect()
                links.recordAutomaticMatch(USER, "book-1", HardcoverMatch(1L, null, HardcoverMatchMethod.ASIN))
                links.recordAutomaticMatch(USER, "book-2", HardcoverMatch(2L, null, HardcoverMatchMethod.ASIN))
                listenedRead("book-2")
                store.commitPage(USER, listOf(book("book-1", 7L to 100L), book("book-2", 8L to 200L)), CURSOR, 9L, seenAt = T0)

                val started = T0 + 1_000L
                store.startFullPull(USER, started)
                store.pullState(USER)!!.cursor shouldBe null
                // The full pull sees book-1's entry again; book-2's is gone from Hardcover.
                store.commitPage(USER, listOf(book("book-1", 7L to 100L)), CURSOR, 9L, seenAt = started)
                store.finishFullPull(USER, startedAt = started, at = started + 5L)

                store.pulledReads(USER) shouldBe listOf(PulledReadRow("book-1", 100L, 7L))
                sources().filter { it.second == "playback" } shouldBe listOf("lu-book-2" to "playback")
                store.pullState(USER) shouldBe HardcoverPullState(CURSOR, 9L, fullPullStartedAt = null, lastFullPullAt = started + 5L)
            }
        }

        test("a changed match forgets that book's pulled reads and asks for a full pull") {
            storeTest {
                connect()
                links.recordAutomaticMatch(USER, "book-1", HardcoverMatch(1L, null, HardcoverMatchMethod.ASIN))
                links.recordAutomaticMatch(USER, "book-2", HardcoverMatch(2L, null, HardcoverMatchMethod.ASIN))
                store.startFullPull(USER, T0)
                store.commitPage(USER, listOf(book("book-1", 7L to 100L), book("book-2", 8L to 200L)), CURSOR, 9L, seenAt = T0)
                store.finishFullPull(USER, startedAt = T0, at = T0)
                store.pullState(USER)!!.lastFullPullAt shouldBe T0

                store.forgetPulledBook(USER, "book-1")
                store.requestFullPull(USER)

                store.pulledReads(USER) shouldBe listOf(PulledReadRow("book-2", 200L, 8L))
                store.pullState(USER)!!.lastFullPullAt shouldBe null
            }
        }

        test("disconnecting, or connecting another account, forgets pulled reads; reconnecting the same account keeps them") {
            storeTest {
                connect(hcUserId = 42)
                listenedRead("book-1")
                store.commitPage(USER, listOf(book("book-1", 7L to 100L)), CURSOR, 9L, T0)

                connect(hcUserId = 42)
                store.pulledReads(USER).size shouldBe 1

                connect(hcUserId = 99)
                store.pulledReads(USER) shouldBe emptyList()

                store.commitPage(USER, listOf(book("book-1", 7L to 100L)), CURSOR, 9L, T0)
                connections.delete(USER)
                store.pulledReads(USER) shouldBe emptyList()
                sources() shouldBe listOf("lu-book-1" to "playback")
            }
        }

        test("the ledger answers which of a set of reads are ListenUp's own") {
            storeTest {
                links.recordPushedRead(USER, 6_980_588L, "book-1")
                links.pushedReadsAmong(USER, listOf(6_980_588L, 7L)) shouldBe setOf(6_980_588L)
                links.pushedReadsAmong(USER, emptyList()) shouldBe emptySet()
            }
        }

        test("a successful pull clears the pull error and stamps the sync; a stuck one records it") {
            storeTest {
                connect()
                connections.recordPullError(USER, "changedSince 500")
                sql.hardcoverConnectionsQueries
                    .selectByUser(USER)
                    .executeAsOne()
                    .pull_error shouldBe "changedSince 500"
                connections.markPulled(USER, T0)
                sql.hardcoverConnectionsQueries.selectByUser(USER).executeAsOne().let {
                    it.pull_error shouldBe null
                    it.last_synced_at shouldBe T0
                }
            }
        }
    })
