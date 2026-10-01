package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

private const val USER = "u1"
private const val BOOK = "book-1"
private val ASIN_MATCH = HardcoverMatch(427_578L, 9_001L, HardcoverMatchMethod.ASIN)

private class LinkRig(
    val dbs: SqlTestDatabases,
) {
    val sql: ListenUpDatabase = dbs.sql
    val clock = MutableClock(Instant.fromEpochMilliseconds(1_779_451_200_000L))
    val links = HardcoverBookLinkStore(sql, clock)
    val connections = HardcoverConnectionStore(sql, HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("secret")), clock)

    init {
        sql.seedTestUser(USER)
        sql.seedTestLibraryAndFolder()
        sql.seedTestBook(BOOK)
    }

    suspend fun connectAs(hcUserId: Long) =
        connections.save(USER, HardcoverMe(hcUserId, "reader-$hcUserId"), HardcoverTokens("at", "rt", 604_800, HARDCOVER_SCOPES))
}

private fun linkTest(block: suspend LinkRig.() -> Unit) = withSqlDatabase { runTest { LinkRig(this@withSqlDatabase).block() } }

/** The per-(user, book) Hardcover link, the pushed-read ledger, and what a disconnect forgets. */
class HardcoverBookLinkStoreTest :
    FunSpec({

        test("an automatic match is recorded as LINKED") {
            linkTest {
                links.recordAutomaticMatch(USER, BOOK, ASIN_MATCH)
                val linked = links.linkFor(USER, BOOK)!!
                linked.isLinked shouldBe true
                linked.hcBookId shouldBe 427_578L
                linked.method shouldBe HardcoverMatchMethod.ASIN
            }
        }

        test("NEEDS_MATCH is recorded when nothing matched confidently") {
            linkTest {
                links.recordAutomaticMatch(USER, BOOK, null)
                val parked = links.linkFor(USER, BOOK)!!
                parked.isLinked shouldBe false
                parked.hcBookId.shouldBeNull()
                parked.method.shouldBeNull()
            }
        }

        test("an automatic match never overrides a manual link") {
            linkTest {
                links.linkManually(USER, BOOK, 111L, 222L)
                links.recordAutomaticMatch(USER, BOOK, ASIN_MATCH)
                links.linkFor(USER, BOOK)!!.hcBookId shouldBe 111L
                links.linkFor(USER, BOOK)!!.method shouldBe HardcoverMatchMethod.MANUAL
            }
        }

        test("linking manually to a different book forgets the Hardcover records of the old one; the same book keeps them") {
            linkTest {
                links.recordAutomaticMatch(USER, BOOK, ASIN_MATCH)
                links.recordOpenRead(USER, BOOK, userBookId = 70L, readId = 80L, listenThrough = 100L)

                links.linkManually(USER, BOOK, 427_578L, 9_002L)
                links.linkFor(USER, BOOK)!!.openHcReadId shouldBe 80L

                links.linkManually(USER, BOOK, 555L, null)
                val changed = links.linkFor(USER, BOOK)!!
                changed.hcUserBookId.shouldBeNull()
                changed.openHcReadId.shouldBeNull()
                changed.hcEditionId.shouldBeNull()
            }
        }

        test("unlinking parks the book as NEEDS_MATCH") {
            linkTest {
                links.recordAutomaticMatch(USER, BOOK, ASIN_MATCH)
                links.unlink(USER, BOOK)
                links.linkFor(USER, BOOK)!!.isLinked shouldBe false
            }
        }

        test("a suppression is cleared only by a different listen-through") {
            linkTest {
                links.recordAutomaticMatch(USER, BOOK, ASIN_MATCH)
                links.suppress(USER, BOOK, listenThrough = 100L)
                links.clearSuppressionUnlessFor(USER, BOOK, 100L)
                links.linkFor(USER, BOOK)!!.suppressedListenThrough shouldBe 100L
                links.clearSuppressionUnlessFor(USER, BOOK, 200L)
                links.linkFor(USER, BOOK)!!.suppressedListenThrough.shouldBeNull()
            }
        }

        test("the pushed-read ledger remembers every read ListenUp opened or continued") {
            linkTest {
                links.recordPushedRead(USER, readId = 80L, bookId = BOOK)
                links.recordPushedRead(USER, readId = 80L, bookId = BOOK)
                links.isPushedRead(USER, 80L) shouldBe true
                links.isPushedRead(USER, 81L) shouldBe false
            }
        }

        test("the background pass lists started books with no link, after the cursor") {
            linkTest {
                sql.seedTestBook("book-2")
                listOf(BOOK, "book-2").forEachIndexed { i, id ->
                    dbs.driver.execute(
                        null,
                        "INSERT INTO playback_positions (id, user_id, book_id, position_ms, last_played_at, created_at, updated_at) " +
                            "VALUES ('p$i', '$USER', '$id', 0, 0, 0, 0)",
                        0,
                    )
                }
                links.recordAutomaticMatch(USER, "book-2", ASIN_MATCH)
                links.unlinkedStartedBooks(USER, after = "", limit = 10) shouldBe listOf(BOOK)
                links.unlinkedStartedBooks(USER, after = BOOK, limit = 10) shouldBe emptyList()
            }
        }

        test("disconnecting forgets links, but keeps the pushed-read ledger") {
            linkTest {
                connectAs(42L)
                links.recordAutomaticMatch(USER, BOOK, ASIN_MATCH)
                links.recordPushedRead(USER, 80L, BOOK)

                connections.delete(USER)

                links.linkFor(USER, BOOK).shouldBeNull()
                links.isPushedRead(USER, 80L) shouldBe true
            }
        }

        test("reconnecting as a different Hardcover account forgets links; the same account keeps them") {
            linkTest {
                connectAs(42L)
                links.recordAutomaticMatch(USER, BOOK, ASIN_MATCH)
                connectAs(42L)
                links.linkFor(USER, BOOK)!!.isLinked shouldBe true

                connectAs(99L)
                links.linkFor(USER, BOOK).shouldBeNull()
            }
        }

        test("push health: a sync clears the error; an error outlasting the cap is recorded") {
            linkTest {
                connectAs(42L)
                connections.hasConnection(USER) shouldBe true
                connections.recordPushError(USER, "update_user_book_read 500")
                connections.pushHealth(USER) shouldBe HardcoverPushHealth(lastSyncedAt = null, pushError = "update_user_book_read 500")
                connections.markSynced(USER, 1_234L)
                connections.pushHealth(USER) shouldBe HardcoverPushHealth(lastSyncedAt = 1_234L, pushError = null)
            }
        }
    })
