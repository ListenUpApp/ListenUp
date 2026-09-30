package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

private const val USER = "u1"

/** After connecting, the user's started books are matched in the background, slowly, and never guessed. */
class HardcoverMatchBackfillTest :
    FunSpec({

        fun seedStarted(
            dbs: SqlTestDatabases,
            vararg bookIds: String,
        ) {
            dbs.sql.seedTestUser(USER)
            dbs.sql.seedTestLibraryAndFolder()
            bookIds.forEachIndexed { i, id ->
                dbs.sql.seedTestBook(id)
                dbs.driver.execute(
                    null,
                    "INSERT INTO playback_positions (id, user_id, book_id, position_ms, last_played_at, created_at, updated_at) " +
                        "VALUES ('p$i', '$USER', '$id', 0, 0, 0, 0)",
                    0,
                )
            }
        }

        fun backfillFor(
            dbs: SqlTestDatabases,
            hardcover: FakeHardcoverLibrary,
            identities: HardcoverBookIdentities,
        ): Pair<HardcoverMatchBackfill, HardcoverConnectionStore> {
            val clock = MutableClock(Instant.fromEpochMilliseconds(1_779_451_200_000L))
            val connections = HardcoverConnectionStore(dbs.sql, HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("secret")), clock)
            val oauth = HardcoverOAuthClient(HttpClient(), "id", "https://hc.test")
            val linker = HardcoverLinker(oauth, hardcover.client(), connections, CoroutineScope(Dispatchers.Unconfined), clock)
            val backfill =
                HardcoverMatchBackfill(
                    links = HardcoverBookLinkStore(dbs.sql, clock),
                    matcher = HardcoverBookMatcher(hardcover.client(), NoWaitRateLimiter()),
                    tokens = HardcoverTokenProvider(oauth, connections, linker, clock),
                    identities = identities,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )
            return backfill to connections
        }

        test("each started book gets a link: a confident match, or NEEDS_MATCH; a second pass asks nothing") {
            withSqlDatabase {
                seedStarted(this, "book-a", "book-b")
                val hardcover = FakeHardcoverLibrary()
                hardcover.addEdition(FakeHardcoverLibrary.Edition(9_001L, 427_578L, "Project Hail Mary", listOf("Andy Weir"), asin = "B08G9RZBTT"))
                val identities =
                    mapOf(
                        "book-a" to BookIdentity(asin = "B08G9RZBTT", title = "Project Hail Mary"),
                        "book-b" to BookIdentity(title = "Unheard Of", primaryAuthor = "Nobody"),
                    )
                val (backfill, connections) = backfillFor(this, hardcover) { identities[it] }
                val links = HardcoverBookLinkStore(sql)
                runTest {
                    connections.save(USER, HardcoverMe(42, "reader"), HardcoverTokens("hc_at_1", "hc_rt_1", 604_800, HARDCOVER_SCOPES))
                    backfill.run(USER)

                    links.linkFor(USER, "book-a")!!.method shouldBe HardcoverMatchMethod.ASIN
                    links.linkFor(USER, "book-b")!!.isLinked shouldBe false
                    val asked = hardcover.operations.size
                    backfill.run(USER)
                    hardcover.operations.size shouldBe asked
                }
            }
        }

        test("a book whose identity is gone is skipped, and the pass still reaches the books after it") {
            withSqlDatabase {
                seedStarted(this, "book-a", "book-b")
                val hardcover = FakeHardcoverLibrary()
                hardcover.addEdition(FakeHardcoverLibrary.Edition(9_001L, 427_578L, "Project Hail Mary", listOf("Andy Weir"), asin = "B08G9RZBTT"))
                val (backfill, connections) =
                    backfillFor(this, hardcover) { if (it == "book-b") BookIdentity(asin = "B08G9RZBTT", title = "Project Hail Mary") else null }
                val links = HardcoverBookLinkStore(sql)
                runTest {
                    connections.save(USER, HardcoverMe(42, "reader"), HardcoverTokens("hc_at_1", "hc_rt_1", 604_800, HARDCOVER_SCOPES))
                    backfill.run(USER)
                    links.linkFor(USER, "book-a").shouldBeNull()
                    links.linkFor(USER, "book-b")!!.method shouldBe HardcoverMatchMethod.ASIN
                }
            }
        }

        test("a Hardcover failure ends the pass without recording a guess") {
            withSqlDatabase {
                seedStarted(this, "book-a")
                val hardcover = FakeHardcoverLibrary()
                hardcover.failNext(FakeReply(HttpStatusCode.ServiceUnavailable))
                val (backfill, connections) =
                    backfillFor(this, hardcover) { BookIdentity(asin = "B08G9RZBTT", title = "Project Hail Mary") }
                runTest {
                    connections.save(USER, HardcoverMe(42, "reader"), HardcoverTokens("hc_at_1", "hc_rt_1", 604_800, HARDCOVER_SCOPES))
                    backfill.run(USER)
                    HardcoverBookLinkStore(sql).linkFor(USER, "book-a").shouldBeNull()
                }
            }
        }

        test("without a connection the pass asks Hardcover nothing") {
            withSqlDatabase {
                seedStarted(this, "book-a")
                val hardcover = FakeHardcoverLibrary()
                val (backfill, _) = backfillFor(this, hardcover) { BookIdentity(asin = "B08G9RZBTT", title = "Project Hail Mary") }
                runTest {
                    backfill.run(USER)
                    hardcover.operations shouldBe emptyList()
                }
            }
        }
    })
