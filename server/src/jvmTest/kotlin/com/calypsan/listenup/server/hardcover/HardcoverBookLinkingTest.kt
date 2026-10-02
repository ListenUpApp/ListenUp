package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookCandidate
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookMatch
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
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
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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
    val refreshes = AtomicInteger()
    private val oauth =
        HardcoverOAuthClient(
            HttpClient(
                MockEngine {
                    refreshes.incrementAndGet()
                    respond(
                        """{"access_token":"hc_at_2","refresh_token":"hc_rt_2","expires_in":604800,"scope":"$HARDCOVER_SCOPES"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ),
            "id",
            "https://hc.test",
        )
    private val linker = HardcoverLinker(oauth, hardcover.client(), connections, CoroutineScope(Dispatchers.Unconfined), clock)
    val pulls = RecordingPullRequests()
    val catalog = HardcoverCatalogCache(hardcover.client(), NoWaitRateLimiter())
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
            pulls = pulls,
            catalog = catalog,
            exclusions = HardcoverExclusions(sql),
        )
    val service =
        HardcoverServiceImpl(
            linker,
            clientIdConfigured = true,
            linking = linking,
            pulls = pulls,
            preferences = HardcoverPreferences(sql),
            history = HardcoverHistorySender(sql),
        )

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
                ratingsCount = 8_107,
                releaseYear = 2021,
            ),
        )
    }

    suspend fun connect() =
        connections.save(USER, HardcoverMe(42, "reader"), HardcoverTokens("hc_at_1", "hc_rt_1", 604_800, HARDCOVER_SCOPES))

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
                        listOf(
                            HardcoverBookCandidate(
                                427_578L,
                                9_001L,
                                "Project Hail Mary",
                                listOf("Andy Weir"),
                                releaseYear = 2021,
                                ratingsCount = 8_107,
                            ),
                        ),
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
                serviceAs(
                    UserRole.ROOT,
                ).searchCatalog("   ").shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<ValidationError>()
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

        test("a throttled search is Unavailable") {
            linkingTest {
                connect()
                hardcover.failNext(FakeReply(HttpStatusCode.TooManyRequests))
                serviceAs(UserRole.ROOT)
                    .searchCatalog("hail mary")
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<HardcoverError.Unavailable>()
            }
        }

        test("a search whose token Hardcover rejects refreshes it once and finds the book") {
            linkingTest {
                connect()
                hardcover.failNext(FakeReply(HttpStatusCode.Unauthorized, """{"error":"invalid_token"}"""))
                serviceAs(UserRole.ROOT)
                    .searchCatalog("hail mary")
                    .shouldBeInstanceOf<AppResult.Success<List<HardcoverBookCandidate>>>()
                    .data
                    .single()
                    .hcBookId shouldBe 427_578L
                refreshes.get() shouldBe 1
                hardcover.operations shouldBe listOf("search", "search", "books_by_ids")
            }
        }

        test("a token Hardcover rejects again after the refresh is ConnectionBroken") {
            linkingTest {
                connect()
                hardcover.failNext(FakeReply(HttpStatusCode.Unauthorized, """{"error":"invalid_token"}"""))
                hardcover.failNext(FakeReply(HttpStatusCode.Unauthorized, """{"error":"invalid_token"}"""))
                serviceAs(UserRole.ROOT)
                    .searchCatalog("hail mary")
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<HardcoverError.ConnectionBroken>()
                refreshes.get() shouldBe 1
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

        test("undoing a changed match puts an ASIN match back as ASIN, at its edition, not as the user's pick") {
            linkingTest {
                connect()
                links.recordAutomaticMatch(USER, BOOK, HardcoverMatch(427_578L, 9_001L, HardcoverMatchMethod.ASIN))
                serviceAs(UserRole.ROOT).linkBook(BookId(BOOK), 555L, 556L) shouldBe AppResult.Success(Unit)

                serviceAs(UserRole.ROOT).restoreMatch(BookId(BOOK), 427_578L, 9_001L, HardcoverMatchMethod.ASIN) shouldBe
                    AppResult.Success(Unit)

                val link = links.linkFor(USER, BOOK)!!
                link.method shouldBe HardcoverMatchMethod.ASIN
                link.hcBookId shouldBe 427_578L
                link.hcEditionId shouldBe 9_001L
                val match =
                    serviceAs(UserRole.ROOT)
                        .bookMatch(BookId(BOOK))
                        .shouldBeInstanceOf<AppResult.Success<HardcoverBookMatch>>()
                        .data
                        .shouldBeInstanceOf<HardcoverBookMatch.Linked>()
                match.method shouldBe HardcoverMatchMethod.ASIN
                match.chosenByYou shouldBe false
                pulls.matchChanges shouldBe listOf(USER to BOOK, USER to BOOK)
            }
        }

        test("restoring a match unparks the book's waiting pushes, as linking does") {
            linkingTest {
                connect()
                outbox.enqueueStart(USER, BOOK, T0, T0, false)
                links.recordAutomaticMatch(USER, BOOK, null)
                serviceAs(UserRole.ROOT).restoreMatch(BookId(BOOK), 427_578L, null, HardcoverMatchMethod.ISBN) shouldBe
                    AppResult.Success(Unit)
                links.linkFor(USER, BOOK)!!.method shouldBe HardcoverMatchMethod.ISBN
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
                service
                    .searchCatalog("x")
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
                service
                    .linkBook(BookId(BOOK), 1L, null)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
                service
                    .unlinkBook(
                        BookId(BOOK),
                    ).shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
                service
                    .restoreMatch(BookId(BOOK), 1L, null, HardcoverMatchMethod.ASIN)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
                service
                    .booksNeedingMatch()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
                service
                    .bookMatch(BookId(BOOK))
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
            }
        }

        test("linking or unlinking a book tells the pull its match changed") {
            linkingTest {
                connect()
                serviceAs(UserRole.ROOT).linkBook(BookId(BOOK), 427_578L, 9_001L) shouldBe AppResult.Success(Unit)
                serviceAs(UserRole.ROOT).unlinkBook(BookId(BOOK)) shouldBe AppResult.Success(Unit)
                pulls.matchChanges shouldBe listOf(USER to BOOK, USER to BOOK)
            }
        }

        test("a search remembers what it found, so naming the pick costs nothing more") {
            linkingTest {
                connect()
                serviceAs(UserRole.ROOT).searchCatalog("hail mary")
                catalog.cached(427_578L)?.title shouldBe "Project Hail Mary"
            }
        }

        test("the books that need a match are listed newest first") {
            linkingTest {
                connect()
                sql.seedTestBook("book-2")
                sql.seedTestBook("book-3")
                links.recordAutomaticMatch(USER, BOOK, null)
                clock.instant = Instant.fromEpochMilliseconds(T0 + 1)
                links.recordAutomaticMatch(USER, "book-2", null)
                links.recordAutomaticMatch(USER, "book-3", HardcoverMatch(1L, null, HardcoverMatchMethod.ASIN))

                serviceAs(UserRole.ROOT).booksNeedingMatch() shouldBe
                    AppResult.Success(listOf(BookId("book-2"), BookId(BOOK)))
            }
        }

        test("a book kept off Hardcover leaves Needs a match") {
            linkingTest {
                connect()
                sql.seedTestBook("book-2")
                links.recordAutomaticMatch(USER, BOOK, null)
                links.recordAutomaticMatch(USER, "book-2", null)
                sql.seedExclusion(USER, BOOK, at = T0)

                serviceAs(UserRole.ROOT).booksNeedingMatch() shouldBe AppResult.Success(listOf(BookId("book-2")))
            }
        }

        test("a book the caller can't see isn't listed") {
            linkingTest {
                connect()
                links.recordAutomaticMatch(USER, BOOK, null)
                serviceAs(UserRole.MEMBER).booksNeedingMatch() shouldBe AppResult.Success(emptyList())
            }
        }

        test("without a connection there is no list to show") {
            linkingTest {
                serviceAs(UserRole.ROOT)
                    .booksNeedingMatch()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<HardcoverError.NotConnected>()
            }
        }

        test("a book never matched is Unmatched; one ListenUp couldn't match Needs a match") {
            linkingTest {
                connect()
                serviceAs(UserRole.ROOT).bookMatch(BookId(BOOK)) shouldBe AppResult.Success(HardcoverBookMatch.Unmatched)
                links.recordAutomaticMatch(USER, BOOK, null)
                serviceAs(UserRole.ROOT).bookMatch(BookId(BOOK)) shouldBe AppResult.Success(HardcoverBookMatch.NeedsMatch)
            }
        }

        test("a book kept off Hardcover is KeptOff, whatever its link") {
            linkingTest {
                connect()
                serviceAs(UserRole.ROOT).linkBook(BookId(BOOK), 427_578L, 9_001L)
                sql.seedExclusion(USER, BOOK, at = T0)

                // Unwrapped on purpose: Kotest's data-class diff passes `Success(<data class>) shouldBe Success(<data object>)`.
                serviceAs(UserRole.ROOT).bookMatch(BookId(BOOK)).shouldSucceed() shouldBe HardcoverBookMatch.KeptOff
                links.linkFor(USER, BOOK)!!.hcBookId shouldBe 427_578L
            }
        }

        test("a linked book says what keeping it off would take out of ListenUp") {
            linkingTest {
                connect()
                links.recordAutomaticMatch(USER, BOOK, HardcoverMatch(427_578L, 9_001L, HardcoverMatchMethod.ASIN))
                sql.seedPulledRead(USER, BOOK, hcReadId = 7L, finishedAt = T0)

                val match =
                    serviceAs(UserRole.ROOT)
                        .bookMatch(BookId(BOOK))
                        .shouldBeInstanceOf<AppResult.Success<HardcoverBookMatch>>()
                        .data
                        .shouldBeInstanceOf<HardcoverBookMatch.Linked>()
                match.readsInReaders shouldBe true
                match.onToReadFromHardcover shouldBe false
            }
        }

        test("a linked book is named from the catalog once, and says the user chose it") {
            linkingTest {
                connect()
                serviceAs(UserRole.ROOT).linkBook(BookId(BOOK), 427_578L, 9_001L)
                val expected =
                    HardcoverBookMatch.Linked(
                        hcBookId = 427_578L,
                        hcEditionId = 9_001L,
                        title = "Project Hail Mary",
                        authors = listOf("Andy Weir"),
                        releaseYear = 2021,
                        chosenByYou = true,
                        sync = HardcoverBookSync.NOTHING_SENT_YET,
                        method = HardcoverMatchMethod.MANUAL,
                    )
                serviceAs(UserRole.ROOT).bookMatch(BookId(BOOK)) shouldBe AppResult.Success(expected)
                serviceAs(UserRole.ROOT).bookMatch(BookId(BOOK)) shouldBe AppResult.Success(expected)
                hardcover.operations.count { it == "books_by_ids" } shouldBe 1
            }
        }

        test("a pending push makes a linked book Waiting") {
            linkingTest {
                connect()
                links.recordAutomaticMatch(USER, BOOK, HardcoverMatch(427_578L, 9_001L, HardcoverMatchMethod.ASIN))
                outbox.enqueueStart(USER, BOOK, T0, T0, false)
                val match =
                    serviceAs(UserRole.ROOT)
                        .bookMatch(BookId(BOOK))
                        .shouldBeInstanceOf<AppResult.Success<HardcoverBookMatch>>()
                        .data
                        .shouldBeInstanceOf<HardcoverBookMatch.Linked>()
                match.sync shouldBe HardcoverBookSync.WAITING
                match.chosenByYou shouldBe false
            }
        }

        test("the deletion rule outranks everything; a shelf entry with nothing waiting is up to date") {
            val link =
                HardcoverBookLink(
                    userId = USER,
                    bookId = BOOK,
                    hcBookId = 1L,
                    hcEditionId = null,
                    method = HardcoverMatchMethod.ISBN,
                    isLinked = true,
                    hcUserBookId = 55L,
                    openHcReadId = null,
                    openReadListenThrough = null,
                    suppressedListenThrough = null,
                    lastProgressPushedAt = null,
                )
            bookSyncOf(link, pending = 0) shouldBe HardcoverBookSync.UP_TO_DATE
            bookSyncOf(link, pending = 2) shouldBe HardcoverBookSync.WAITING
            bookSyncOf(link.copy(suppressedListenThrough = T0), pending = 2) shouldBe HardcoverBookSync.REMOVED_ON_HARDCOVER
            bookSyncOf(link.copy(hcUserBookId = null), pending = 0) shouldBe HardcoverBookSync.NOTHING_SENT_YET
        }

        test("a book the caller can't see is NotFound; no connection is NotConnected") {
            linkingTest {
                connect()
                serviceAs(UserRole.ROOT)
                    .bookMatch(BookId("ghost"))
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<BookError.NotFound>()
            }
            linkingTest {
                serviceAs(UserRole.ROOT)
                    .bookMatch(BookId(BOOK))
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<HardcoverError.NotConnected>()
            }
        }
    })
