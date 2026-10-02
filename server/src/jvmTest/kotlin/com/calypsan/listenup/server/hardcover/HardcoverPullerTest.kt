package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.ShelfBookRepository
import com.calypsan.listenup.server.sync.ShelfRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.Instant

private const val USER = "u1"
private const val BOOK = "book-1"
private const val HC_BOOK = 427_578L

// 2026-05-22 12:00:00 UTC.
private const val T0 = 1_779_451_200_000L

/** Noon UTC (the test user's home zone) on [date]: where a pulled read's date lands. */
internal fun noonUtc(date: String): Long =
    LocalDate
        .parse(date)
        .atTime(12, 0)
        .toInstant(TimeZone.UTC)
        .toEpochMilliseconds()

internal class PullRig(
    val dbs: SqlTestDatabases,
    readChangesTouchShelf: Boolean = true,
) {
    val sql = dbs.sql
    val clock = MutableClock(Instant.fromEpochMilliseconds(T0))
    val hardcover = FakeHardcoverLibrary(readChangesTouchShelf = readChangesTouchShelf)
    val store = HardcoverPullStore(sql, clock)
    val links = HardcoverBookLinkStore(sql, clock)
    val outbox = HardcoverOutbox(sql, clock)
    val userBooks = HardcoverUserBooks(hardcover.client())
    val bus = ChangeBus()
    private val registry = SyncRegistry()
    val shelves = ShelfRepository(sql, bus, registry, clock)
    val shelfBooks = ShelfBookRepository(sql, bus, registry, clock)
    val shelfEntries = HardcoverShelfEntryStore(sql, clock)
    val wantToRead = HardcoverWantToRead(sql, shelfEntries, shelves, shelfBooks, BookAccessPolicy(sql, dbs.driver))
    val connections =
        HardcoverConnectionStore(sql, HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("secret")), clock, wantToRead = wantToRead)
    val puller =
        HardcoverPuller(
            userBooks = userBooks,
            store = store,
            resolver = HardcoverShelfResolver(sql, BookAccessPolicy(sql, dbs.driver), HardcoverExclusions(sql)),
            links = links,
            wantToRead = wantToRead,
            rateLimiter = NoWaitRateLimiter(),
            sql = sql,
            clock = clock,
        )

    init {
        sql.seedTestUser(USER, userRole = UserRoleColumn.ROOT)
        sql.seedTestLibraryAndFolder()
        sql.seedTestBook(BOOK, asin = "B005UR3VFO")
        hardcover.addEdition(
            FakeHardcoverLibrary.Edition(
                id = 9_001L,
                bookId = HC_BOOK,
                title = "11/22/63",
                authors = listOf("Stephen King"),
                asin = "B005UR3VFO",
                readingFormatId = 2,
                defaultAudioEditionId = 9_001L,
            ),
        )
    }

    suspend fun connect(hcUserId: Long = 42) =
        connections.save(USER, HardcoverMe(hcUserId, "reader"), HardcoverTokens("hc_at_1", "hc_rt_1", 604_800, HARDCOVER_SCOPES))

    suspend fun pull(): PullProgress =
        puller
            .pullPage(USER, "hc_at_1")
            .shouldBeInstanceOf<HardcoverCall.Ok<PullProgress>>()
            .value

    suspend fun pullAll() {
        while (pull() == PullProgress.MORE_PAGES) Unit
    }

    fun lastAfter(): String =
        hardcover.requests
            .last { it.operation == "user_books_changed" }
            .variables
            .getValue("after")
            .jsonPrimitive
            .content
}

internal fun pullTest(
    readChangesTouchShelf: Boolean = true,
    block: suspend PullRig.() -> Unit,
) = withSqlDatabase { runTest { PullRig(this@withSqlDatabase, readChangesTouchShelf).block() } }

/** A [HardcoverWantToRead] over [dbs] with shelves of its own, for rigs that never look at a shelf. */
internal fun testWantToRead(
    dbs: SqlTestDatabases,
    clock: Clock,
): HardcoverWantToRead {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    return HardcoverWantToRead(
        dbs.sql,
        HardcoverShelfEntryStore(dbs.sql, clock),
        ShelfRepository(dbs.sql, bus, registry, clock),
        ShelfBookRepository(dbs.sql, bus, registry, clock),
        BookAccessPolicy(dbs.sql, dbs.driver),
    )
}

/** One page of spec B3's pull: fetch what changed, resolve it, mirror its finished reads, move the cursor. */
class HardcoverPullerTest :
    FunSpec({

        test("a finished read of a library book arrives as a Hardcover read at noon on its date, and the match becomes a link") {
            pullTest {
                connect()
                val shelf = hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2017-01-02" to "2017-03-01", editionId = 9_001L)

                pull() shouldBe PullProgress.CAUGHT_UP

                store.pulledReads(USER) shouldBe listOf(PulledReadRow(BOOK, noonUtc("2017-03-01"), shelf.reads.single().id))
                links.linkFor(USER, BOOK)!!.method shouldBe HardcoverMatchMethod.ASIN
            }
        }

        test("a book kept off Hardcover brings nothing in: no reads, no link, and Want to Read never shelves it") {
            pullTest {
                connect()
                sql.seedExclusion(USER, BOOK, at = T0)
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2017-01-02" to "2017-03-01", editionId = 9_001L)

                pullAll()
                store.pulledReads(USER) shouldBe emptyList()
                links.linkFor(USER, BOOK) shouldBe null

                hardcover.moveTo(HC_BOOK, HardcoverStatus.WANT_TO_READ)
                pullAll()
                shelfEntries.recordFor(USER, BOOK) shouldBe null
            }
        }

        test("the first pull is a full pull from the start; the next, within a day, starts at the cursor") {
            pullTest {
                connect()
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2017-01-02" to "2017-03-01", editionId = 9_001L)
                pull()
                lastAfter() shouldBe PULL_EPOCH
                store.pullState(USER)!!.lastFullPullAt shouldBe T0

                pull()
                lastAfter() shouldBe hardcover.shelfFor(HC_BOOK)!!.updatedAt
            }
        }

        test("the cursor is Hardcover's text, stored and sent back verbatim — a trimmed fraction stays trimmed") {
            pullTest {
                connect()
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2017-01-02" to "2017-03-01", editionId = 9_001L)
                hardcover.setUpdatedAt(HC_BOOK, "2026-09-30T18:04:19.1+00:00")
                pull()
                store.pullState(USER)!!.cursor shouldBe "2026-09-30T18:04:19.1+00:00"

                pull()
                lastAfter() shouldBe "2026-09-30T18:04:19.1+00:00"
            }
        }

        test("a full page asks for more; a short page catches up") {
            pullTest {
                connect()
                (1L..PULL_PAGE_SIZE.toLong() + 1).forEach { hardcover.seedShelf(it, HardcoverStatus.READ, null to null) }
                pull() shouldBe PullProgress.MORE_PAGES
                pull() shouldBe PullProgress.CAUGHT_UP
                hardcover.operations shouldBe listOf("user_books_changed", "user_books_changed")
            }
        }

        test("a page Hardcover fails writes nothing and moves no cursor") {
            pullTest {
                connect()
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2017-01-02" to "2017-03-01", editionId = 9_001L)
                hardcover.failNext(FakeReply(HttpStatusCode.InternalServerError))

                puller.pullPage(USER, "hc_at_1").shouldBeInstanceOf<HardcoverCall.Failed>()

                store.pulledReads(USER) shouldBe emptyList()
                store.pullState(USER)!!.cursor shouldBe null
            }
        }

        test("a read finished after today is not pulled; one finished today or yesterday is") {
            pullTest {
                connect()
                val shelf =
                    hardcover.seedShelf(
                        HC_BOOK,
                        HardcoverStatus.READ,
                        "2026-05-01" to "2026-05-21",
                        "2026-05-21" to "2026-05-22",
                        "2026-05-22" to "2026-05-23",
                        editionId = 9_001L,
                    )
                val (yesterday, today, _) = shelf.reads.map { it.id }

                pull() shouldBe PullProgress.CAUGHT_UP

                store.pulledReads(USER).map { it.hcReadId } shouldBe listOf(yesterday, today)
            }
        }

        test("today is the user's own date: far east of UTC, tomorrow-in-UTC is already today") {
            pullTest {
                connect()
                sql.usersQueries.updateTimezone(timezone = "Pacific/Kiritimati", id = USER)
                val shelf =
                    hardcover.seedShelf(
                        HC_BOOK,
                        HardcoverStatus.READ,
                        "2026-05-20" to "2026-05-23",
                        "2026-05-20" to "2026-05-24",
                        editionId = 9_001L,
                    )

                pull()

                store.pulledReads(USER).map { it.hcReadId } shouldBe listOf(shelf.reads.first().id)
            }
        }

        test("open reads, undated Read books and books outside the library pull nothing, and the cursor passes them") {
            pullTest {
                connect()
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READING, "2026-05-01" to null, editionId = 9_001L)
                hardcover.seedShelf(555L, HardcoverStatus.READ, null to null)
                val stranger = hardcover.seedShelf(777L, HardcoverStatus.READ, "2015-01-01" to "2015-02-01")

                pull() shouldBe PullProgress.CAUGHT_UP

                store.pulledReads(USER) shouldBe emptyList()
                store.pullState(USER)!!.cursorId shouldBe stranger.id
            }
        }

        test("a shelf write that fails is the page's failure, so the cursor stays where it was") {
            AppResult.Success(Unit).asPullFailure() shouldBe null
            AppResult
                .Failure(SyncError.NotFound(domain = "shelf_books", entityId = "x"))
                .asPullFailure()
                .shouldBeInstanceOf<HardcoverCall.Failed>()
                .detail shouldContain "want to read"
        }
    })
