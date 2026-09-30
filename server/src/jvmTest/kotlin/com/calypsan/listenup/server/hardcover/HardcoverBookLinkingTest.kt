package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookCandidate
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.HardcoverServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Instant

private const val USER = "u1"
private const val BOOK = "book-1"
private const val T0 = 1_779_451_200_000L

private class LinkingRig(
    dbs: SqlTestDatabases,
) {
    val sql = dbs.sql
    val clock = MutableClock(Instant.fromEpochMilliseconds(T0))
    val hardcover = FakeHardcoverLibrary()
    val connections = HardcoverConnectionStore(sql, HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("secret")), clock)
    val links = HardcoverBookLinkStore(sql, clock)
    val outbox = HardcoverOutbox(sql, clock)
    val nudged = CopyOnWriteArrayList<String>()
    private val oauth = HardcoverOAuthClient(HttpClient(), "id", "https://hc.test")
    private val linker = HardcoverLinker(oauth, hardcover.client(), connections, CoroutineScope(Dispatchers.Unconfined), clock)
    val linking =
        HardcoverBookLinking(
            graphQl = hardcover.client(),
            tokens = HardcoverTokenProvider(oauth, connections, linker, clock),
            connections = connections,
            links = links,
            outbox = outbox,
            nudge = HardcoverPushNudge { nudged += it },
            access = BookAccessPolicy(dbs.sql, dbs.driver),
            rateLimiter = NoWaitRateLimiter(),
        )
    val service = HardcoverServiceImpl(linker, clientIdConfigured = true, linking = linking)

    init {
        sql.seedTestUser(USER)
        sql.seedTestLibraryAndFolder()
        sql.seedTestBook(BOOK)
        hardcover.addEdition(
            FakeHardcoverLibrary.Edition(
                9_001L,
                427_578L,
                "Project Hail Mary",
                listOf("Andy Weir"),
                readingFormatId = 2,
                defaultAudioEditionId = 9_001L,
            ),
        )
    }

    suspend fun connect() = connections.save(USER, HardcoverMe(42, "reader"), HardcoverTokens("hc_at_1", "hc_rt_1", 604_800, HARDCOVER_SCOPES))

    fun serviceAs(role: UserRole) = service.copyWith(PrincipalProvider { UserPrincipal(UserId(USER), SessionId("s"), role) })
}

private fun linkingTest(block: suspend LinkingRig.() -> Unit) = withSqlDatabase { runTest { LinkingRig(this@withSqlDatabase).block() } }

/** B4's manual side: search the catalog, link a book by hand (unparking its pushes), and unlink it. */
class HardcoverBookLinkingTest :
    FunSpec({

        test("search finds candidates with the edition to shelve") {
            linkingTest {
                connect()
                serviceAs(UserRole.ROOT).searchCatalog("hail mary") shouldBe
                    AppResult.Success(
                        listOf(HardcoverBookCandidate(427_578L, 9_001L, "Project Hail Mary", listOf("Andy Weir"), releaseYear = null)),
                    )
                hardcover.operations shouldBe listOf("search", "books_by_ids")
            }
        }

        test("a search with no hits asks Hardcover once and finds nothing") {
            linkingTest {
                connect()
                serviceAs(UserRole.ROOT).searchCatalog("nothing like it") shouldBe AppResult.Success(emptyList())
                hardcover.operations shouldBe listOf("search")
            }
        }

        test("a blank search is a validation error and never reaches Hardcover") {
            linkingTest {
                connect()
                serviceAs(UserRole.ROOT).searchCatalog("   ").shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<ValidationError>()
                hardcover.operations shouldBe emptyList()
            }
        }

        test("searching without a connection is NotConnected") {
            linkingTest {
                serviceAs(UserRole.ROOT)
                    .searchCatalog("hail mary")
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<HardcoverError.NotConnected>()
            }
        }

        test("a throttled search is Unavailable; a rejected token is ConnectionBroken") {
            linkingTest {
                connect()
                hardcover.failNext(FakeReply(HttpStatusCode.TooManyRequests))
                serviceAs(UserRole.ROOT)
                    .searchCatalog("hail mary")
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<HardcoverError.Unavailable>()
                hardcover.failNext(FakeReply(HttpStatusCode.Unauthorized))
                serviceAs(UserRole.ROOT)
                    .searchCatalog("hail mary")
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<HardcoverError.ConnectionBroken>()
            }
        }

        test("linking by hand sets MANUAL, unparks the book's queued pushes, and nudges the lane") {
            linkingTest {
                connect()
                outbox.enqueueStart(USER, BOOK, T0, T0, false)
                links.recordAutomaticMatch(USER, BOOK, null)
                outbox.head(USER).shouldBeNull()

                serviceAs(UserRole.ROOT).linkBook(BookId(BOOK), 427_578L, 9_001L) shouldBe AppResult.Success(Unit)

                links.linkFor(USER, BOOK)!!.method shouldBe HardcoverMatchMethod.MANUAL
                outbox.head(USER)!!.bookId shouldBe BOOK
                nudged shouldBe listOf(USER)
            }
        }

        test("linking to an id that can't be a Hardcover book is a validation error") {
            linkingTest {
                connect()
                serviceAs(UserRole.ROOT)
                    .linkBook(BookId(BOOK), 0L, null)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<ValidationError>()
                links.linkFor(USER, BOOK).shouldBeNull()
            }
        }

        test("linking a book the caller can't see is BookError.NotFound") {
            linkingTest {
                connect()
                serviceAs(UserRole.ROOT)
                    .linkBook(BookId("ghost"), 427_578L, null)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<BookError.NotFound>()
            }
        }

        test("linking without a connection is NotConnected") {
            linkingTest {
                serviceAs(UserRole.ROOT)
                    .linkBook(BookId(BOOK), 427_578L, null)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<HardcoverError.NotConnected>()
            }
        }

        test("unlinking parks the book as NEEDS_MATCH") {
            linkingTest {
                connect()
                links.recordAutomaticMatch(USER, BOOK, HardcoverMatch(427_578L, 9_001L, HardcoverMatchMethod.ASIN))
                serviceAs(UserRole.ROOT).unlinkBook(BookId(BOOK)) shouldBe AppResult.Success(Unit)
                links.linkFor(USER, BOOK)!!.isLinked shouldBe false
            }
        }

        test("every new method fails closed without a caller") {
            linkingTest {
                service.searchCatalog("x").shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                service
                    .linkBook(BookId(BOOK), 1L, null)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
                service.unlinkBook(BookId(BOOK)).shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AuthError.PermissionDenied>()
            }
        }
    })
