package com.calypsan.listenup.server.api

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverHistory
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.hardcover.HARDCOVER_SCOPES
import com.calypsan.listenup.server.hardcover.HardcoverBookLinkStore
import com.calypsan.listenup.server.hardcover.HardcoverBookLinking
import com.calypsan.listenup.server.hardcover.HardcoverCatalogCache
import com.calypsan.listenup.server.hardcover.HardcoverConnectionStore
import com.calypsan.listenup.server.hardcover.HardcoverExclusions
import com.calypsan.listenup.server.hardcover.HardcoverGraphQlClient
import com.calypsan.listenup.server.hardcover.HardcoverHistoryProgress
import com.calypsan.listenup.server.hardcover.HardcoverHistorySender
import com.calypsan.listenup.server.hardcover.HardcoverKeepOff
import com.calypsan.listenup.server.hardcover.HardcoverLinker
import com.calypsan.listenup.server.hardcover.HardcoverMe
import com.calypsan.listenup.server.hardcover.HardcoverOAuthClient
import com.calypsan.listenup.server.hardcover.HardcoverOutbox
import com.calypsan.listenup.server.hardcover.HardcoverPreferences
import com.calypsan.listenup.server.hardcover.HardcoverPushNudge
import com.calypsan.listenup.server.hardcover.HardcoverRateLimiter
import com.calypsan.listenup.server.hardcover.HardcoverSyncActivity
import com.calypsan.listenup.server.hardcover.RecordingPullRequests
import com.calypsan.listenup.server.hardcover.HardcoverTokenCipher
import com.calypsan.listenup.server.hardcover.HardcoverTokenProvider
import com.calypsan.listenup.server.hardcover.HardcoverTokens
import com.calypsan.listenup.server.hardcover.HardcoverUserGate
import com.calypsan.listenup.server.hardcover.hardcoverShareMode
import com.calypsan.listenup.server.hardcover.seedOwnRead
import com.calypsan.listenup.server.hardcover.testWantToRead
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock

private const val USER = "u1"
private const val OTHER_USER = "u2"

private val JSON = headersOf(HttpHeaders.ContentType, "application/json")

private const val DEVICE_JSON =
    """{"device_code":"dev-1","user_code":"ABCD-1234","verification_uri":"https://hardcover.app/link",""" +
        """"verification_uri_complete":"https://hardcover.app/link?code=ABCD-1234","expires_in":600,"interval":5}"""
private const val GRANT_JSON =
    """{"access_token":"hc_at_1","refresh_token":"hc_rt_1","expires_in":604800,"scope":"$HARDCOVER_SCOPES"}"""
private const val ME_SIMON = """{"data":{"me":[{"id":42,"username":"simon"}]}}"""

/**
 * A scripted Hardcover, in the style of `HardcoverConnectionsTest`'s: the device endpoint starts a
 * sign-in, the first poll grants, `me` answers "simon", and a revoke succeeds. Every request path
 * is recorded, so a test can prove which calls reached Hardcover (and that some never did).
 */
private class ScriptedHardcover {
    val paths = CopyOnWriteArrayList<String>()

    val client =
        HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                paths += path
                when (path) {
                    "/oauth2/device" -> respond(DEVICE_JSON, HttpStatusCode.OK, JSON)
                    "/oauth2/token" -> respond(GRANT_JSON, HttpStatusCode.OK, JSON)
                    "/v1/graphql" -> respond(ME_SIMON, HttpStatusCode.OK, JSON)
                    else -> respond("{}", HttpStatusCode.OK, JSON)
                }
            },
        )
}

private fun principalOf(userId: String) = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("session-$userId"), UserRole.MEMBER) }

/** The service over the real linker and store, a real SQLite file, and [ScriptedHardcover]. */
private class Rig(
    dbs: SqlTestDatabases,
    scope: TestScope,
    clientIdConfigured: Boolean,
) {
    val hardcover = ScriptedHardcover()
    val sql = dbs.sql
    val activity = HardcoverSyncActivity()
    val store =
        HardcoverConnectionStore(
            dbs.sql,
            HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("a-jwt-secret")),
            activity = activity,
        )
    private val linker =
        HardcoverLinker(
            HardcoverOAuthClient(hardcover.client, "listenup-test", "https://hc.test"),
            HardcoverGraphQlClient(hardcover.client, "https://hc.test"),
            store,
            scope.backgroundScope,
            activity = activity,
        )
    private val preferences = HardcoverPreferences(dbs.sql, activity = activity)
    private val history = HardcoverHistorySender(dbs.sql, activity = activity)
    private val tokenProvider =
        HardcoverTokenProvider(HardcoverOAuthClient(hardcover.client, "listenup-test", "https://hc.test"), store, linker)
    val pulls = RecordingPullRequests()
    val keepOff =
        HardcoverKeepOff(
            sql = dbs.sql,
            access = BookAccessPolicy(dbs.sql, dbs.driver),
            connections = store,
            wantToRead = testWantToRead(dbs, Clock.System),
            pulls = pulls,
            nudge = HardcoverPushNudge { },
            gate = HardcoverUserGate(),
            bus = ChangeBus(),
            history = HardcoverHistoryProgress(dbs.sql),
            activity = activity,
        )
    val unscoped =
        HardcoverServiceImpl(
            linker,
            clientIdConfigured,
            HardcoverBookLinking(
                graphQl = HardcoverGraphQlClient(hardcover.client, "https://hc.test"),
                tokens = tokenProvider,
                connections = store,
                links = HardcoverBookLinkStore(dbs.sql),
                outbox = HardcoverOutbox(dbs.sql),
                nudge = HardcoverPushNudge { },
                access = BookAccessPolicy(dbs.sql, dbs.driver),
                rateLimiter = HardcoverRateLimiter(),
                pulls = pulls,
                catalog =
                    HardcoverCatalogCache(HardcoverGraphQlClient(hardcover.client, "https://hc.test"), HardcoverRateLimiter()),
                exclusions = HardcoverExclusions(dbs.sql),
            ),
            pulls = pulls,
            preferences = preferences,
            history = history,
            keepOff = keepOff,
        )

    fun serviceFor(userId: String) = unscoped.copyWith(principalOf(userId))

    suspend fun seedConnected(userId: String) =
        store.save(userId, HardcoverMe(42, "simon"), HardcoverTokens("hc_at_1", "hc_rt_1", 604_800, HARDCOVER_SCOPES))
}

private fun serviceTest(
    clientIdConfigured: Boolean = true,
    block: suspend Rig.() -> Unit,
) = withSqlDatabase {
    sql.seedTestUser(USER)
    sql.seedTestUser(OTHER_USER)
    val dbs = this
    runTest { Rig(dbs, this, clientIdConfigured).block() }
}

/** [HardcoverServiceImpl]: the caller-scoped RPC face of the Hardcover connection. */
class HardcoverServiceImplTest :
    FunSpec({

        test("startLink answers NotConfigured when this server has no Hardcover client id, without calling Hardcover") {
            serviceTest(clientIdConfigured = false) {
                serviceFor(USER)
                    .startLink()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<HardcoverError.NotConfigured>()
                hardcover.paths shouldBe emptyList()
            }
        }

        test("with no Hardcover client id and no connection, the stream says Hardcover isn't offered here") {
            serviceTest(clientIdConfigured = false) {
                serviceFor(USER).observeConnection().test {
                    awaitItem() shouldBe RpcEvent.Data(HardcoverConnection.NotOffered)
                }
            }
        }

        test("with no Hardcover client id, an existing connection still shows, so it can be ended") {
            serviceTest(clientIdConfigured = false) {
                seedConnected(USER)
                serviceFor(USER).observeConnection().test {
                    awaitItem()
                        .shouldBeInstanceOf<RpcEvent.Data<HardcoverConnection>>()
                        .value
                        .shouldBeInstanceOf<HardcoverConnection.Connected>()
                }
            }
        }

        test("without a principal every method is PermissionDenied, and the stream emits the denial and ends") {
            serviceTest {
                unscoped
                    .startLink()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
                unscoped
                    .disconnect()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
                val events = unscoped.observeConnection().toList()
                events.size shouldBe 1
                events
                    .single()
                    .shouldBeInstanceOf<RpcEvent.Error>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
                unscoped
                    .syncNow()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
                unscoped
                    .syncIfStale()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
                pulls.syncNowCalls shouldBe emptyList()
                pulls.staleChecks shouldBe emptyList()
                hardcover.paths shouldBe emptyList()
            }
        }

        test("Sync now and the foreground nudge act for the caller, and Sync now passes on the pull's answer") {
            serviceTest {
                serviceFor(USER).syncNow() shouldBe AppResult.Success(Unit)
                serviceFor(OTHER_USER).syncIfStale() shouldBe AppResult.Success(Unit)
                pulls.syncNowCalls shouldBe listOf(USER)
                pulls.staleChecks shouldBe listOf(OTHER_USER)

                pulls.syncNowResult = AppResult.Failure(HardcoverError.NotConnected())
                serviceFor(USER)
                    .syncNow()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<HardcoverError.NotConnected>()
            }
        }

        test("observeConnection emits the caller's current state first, then follows a sign-in to Connected") {
            serviceTest {
                val service = serviceFor(USER)
                service.observeConnection().test {
                    awaitItem() shouldBe RpcEvent.Data(HardcoverConnection.NotConnected())

                    service.startLink().shouldBeInstanceOf<AppResult.Success<*>>()

                    awaitItem()
                        .shouldBeInstanceOf<RpcEvent.Data<HardcoverConnection>>()
                        .value
                        .shouldBeInstanceOf<HardcoverConnection.Linking>()
                    awaitItem()
                        .shouldBeInstanceOf<RpcEvent.Data<HardcoverConnection>>()
                        .value
                        .shouldBeInstanceOf<HardcoverConnection.Connected>()
                        .hardcoverUsername shouldBe "simon"
                }
            }
        }

        test("observeConnection starts from a stored connection") {
            serviceTest {
                seedConnected(USER)
                serviceFor(USER).observeConnection().test {
                    awaitItem()
                        .shouldBeInstanceOf<RpcEvent.Data<HardcoverConnection>>()
                        .value
                        .shouldBeInstanceOf<HardcoverConnection.Connected>()
                }
            }
        }

        test("disconnect is idempotent: the second call succeeds and asks Hardcover for nothing more") {
            serviceTest {
                seedConnected(USER)
                val service = serviceFor(USER)

                service.disconnect() shouldBe AppResult.Success(Unit)
                val revokesAfterFirst = hardcover.paths.count { it == "/oauth2/revoke" }
                revokesAfterFirst shouldBe 2
                store.connectionFor(USER).shouldBeNull()

                service.disconnect() shouldBe AppResult.Success(Unit)
                hardcover.paths.count { it == "/oauth2/revoke" } shouldBe revokesAfterFirst
                service.observeConnection().test {
                    awaitItem() shouldBe RpcEvent.Data(HardcoverConnection.NotConnected())
                }
            }
        }

        test("two users' connections never cross") {
            serviceTest {
                seedConnected(USER)
                val mine = serviceFor(USER)
                val theirs = serviceFor(OTHER_USER)

                theirs.observeConnection().test {
                    awaitItem() shouldBe RpcEvent.Data(HardcoverConnection.NotConnected())
                }
                theirs.disconnect() shouldBe AppResult.Success(Unit)

                mine.observeConnection().test {
                    awaitItem()
                        .shouldBeInstanceOf<RpcEvent.Data<HardcoverConnection>>()
                        .value
                        .shouldBeInstanceOf<HardcoverConnection.Connected>()
                }
                store.connectionFor(USER).shouldNotBeNull()
                hardcover.paths shouldBe emptyList()
            }
        }

        test("setShareMode records the caller's choice, and their Connected carries it live, asking Hardcover nothing") {
            serviceTest {
                seedConnected(USER)
                val service = serviceFor(USER)
                service.observeConnection().test {
                    awaitItem()
                        .shouldBeInstanceOf<RpcEvent.Data<HardcoverConnection>>()
                        .value
                        .shouldBeInstanceOf<HardcoverConnection.Connected>()
                        .shareMode shouldBe HardcoverShareMode.AS_I_LISTEN

                    service.setShareMode(HardcoverShareMode.FINISHED_ONLY) shouldBe AppResult.Success(Unit)

                    awaitItem()
                        .shouldBeInstanceOf<RpcEvent.Data<HardcoverConnection>>()
                        .value
                        .shouldBeInstanceOf<HardcoverConnection.Connected>()
                        .shareMode shouldBe HardcoverShareMode.FINISHED_ONLY
                }
                sql.hardcoverShareMode(OTHER_USER) shouldBe HardcoverShareMode.AS_I_LISTEN
                hardcover.paths shouldBe emptyList()
            }
        }

        test("without a principal setShareMode is PermissionDenied and records nothing") {
            serviceTest {
                unscoped
                    .setShareMode(HardcoverShareMode.FINISHED_ONLY)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
                sql.hardcoverShareMode(USER) shouldBe HardcoverShareMode.AS_I_LISTEN
            }
        }

        test("sendHistory queues the caller's earlier books, and their Connected carries Sending live") {
            serviceTest {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")
                sql.seedOwnRead(USER, "book-1", "r1", finishedAt = 1_000L)
                seedConnected(USER)
                val service = serviceFor(USER)
                service.observeConnection().test {
                    awaitItem()
                        .shouldBeInstanceOf<RpcEvent.Data<HardcoverConnection>>()
                        .value
                        .shouldBeInstanceOf<HardcoverConnection.Connected>()
                        .history shouldBe HardcoverHistory.Offer(bookCount = 1)

                    service.sendHistory() shouldBe AppResult.Success(Unit)

                    awaitItem()
                        .shouldBeInstanceOf<RpcEvent.Data<HardcoverConnection>>()
                        .value
                        .shouldBeInstanceOf<HardcoverConnection.Connected>()
                        .history shouldBe HardcoverHistory.Sending(sentBooks = 0, totalBooks = 1)
                }
                hardcover.paths.filter { it == "/v1/graphql" } shouldBe emptyList()
            }
        }

        test("dismissHistory turns the caller's offer into the quiet row, live") {
            serviceTest {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")
                sql.seedOwnRead(USER, "book-1", "r1", finishedAt = 1_000L)
                seedConnected(USER)
                val service = serviceFor(USER)
                service.observeConnection().test {
                    awaitItem()
                    service.dismissHistory() shouldBe AppResult.Success(Unit)
                    awaitItem()
                        .shouldBeInstanceOf<RpcEvent.Data<HardcoverConnection>>()
                        .value
                        .shouldBeInstanceOf<HardcoverConnection.Connected>()
                        .history shouldBe HardcoverHistory.Available(bookCount = 1)
                }
            }
        }

        test("sendHistory without a connection is NotConnected") {
            serviceTest {
                serviceFor(USER)
                    .sendHistory()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<HardcoverError.NotConnected>()
            }
        }

        test("without a principal sendHistory and dismissHistory are PermissionDenied") {
            serviceTest {
                unscoped
                    .sendHistory()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
                unscoped
                    .dismissHistory()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
            }
        }

        test("setBookSynced keeps the caller's book off Hardcover and syncs it again; keptOffBooks lists it") {
            serviceTest {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")
                val root = unscoped.copyWith(PrincipalProvider { UserPrincipal(UserId(USER), SessionId("s"), UserRole.ROOT) })
                val other = unscoped.copyWith(PrincipalProvider { UserPrincipal(UserId(OTHER_USER), SessionId("t"), UserRole.ROOT) })

                root.setBookSynced(BookId("book-1"), synced = false) shouldBe AppResult.Success(Unit)
                root.keptOffBooks() shouldBe AppResult.Success(listOf(BookId("book-1")))
                other.keptOffBooks() shouldBe AppResult.Success(emptyList())

                root.setBookSynced(BookId("book-1"), synced = true) shouldBe AppResult.Success(Unit)
                root.keptOffBooks() shouldBe AppResult.Success(emptyList())
                hardcover.paths shouldBe emptyList()
            }
        }

        test("the caller's Connected carries the kept-off count live") {
            serviceTest {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")
                seedConnected(USER)
                val root = unscoped.copyWith(PrincipalProvider { UserPrincipal(UserId(USER), SessionId("s"), UserRole.ROOT) })
                root.observeConnection().test {
                    awaitItem()
                        .shouldBeInstanceOf<RpcEvent.Data<HardcoverConnection>>()
                        .value
                        .shouldBeInstanceOf<HardcoverConnection.Connected>()
                        .keptOffBookCount shouldBe 0

                    root.setBookSynced(BookId("book-1"), synced = false) shouldBe AppResult.Success(Unit)

                    awaitItem()
                        .shouldBeInstanceOf<RpcEvent.Data<HardcoverConnection>>()
                        .value
                        .shouldBeInstanceOf<HardcoverConnection.Connected>()
                        .keptOffBookCount shouldBe 1
                }
            }
        }

        test("a book the caller can't see is not found; without a principal both are PermissionDenied") {
            serviceTest {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")
                serviceFor(USER)
                    .setBookSynced(BookId("book-1"), synced = false)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<com.calypsan.listenup.api.error.BookError.NotFound>()
                unscoped
                    .setBookSynced(BookId("book-1"), synced = false)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
                unscoped
                    .keptOffBooks()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.PermissionDenied>()
            }
        }
    })
