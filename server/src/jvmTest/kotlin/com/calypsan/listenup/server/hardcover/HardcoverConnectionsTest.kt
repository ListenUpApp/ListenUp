package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkFailure
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkPrompt
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Instant

private const val BASE_EPOCH_MS = 1_750_000_000_000L
private const val USER = "u1"
private const val OTHER_USER = "u2"
private const val WEEK_SECONDS = 604_800L

private val JSON = headersOf(HttpHeaders.ContentType, "application/json")

private fun deviceJson(
    expiresIn: Long = 600,
    interval: Long = 5,
) = """{"device_code":"dev-1","user_code":"ABCD-1234","verification_uri":"https://hardcover.app/link",""" +
    """"verification_uri_complete":"https://hardcover.app/link?code=ABCD-1234",""" +
    """"expires_in":$expiresIn,"interval":$interval}"""

private fun tokenJson(
    access: String,
    refresh: String,
    expiresIn: Long = WEEK_SECONDS,
) = """{"access_token":"$access","refresh_token":"$refresh","expires_in":$expiresIn,"scope":"$HARDCOVER_SCOPES"}"""

private fun oauthError(code: String) = """{"error":"$code"}"""

private const val ME_SIMON = """{"data":{"me":[{"id":42,"username":"simon"}]}}"""

/** One request Hardcover saw: which endpoint (poll and refresh split by grant), its form, and the virtual time. */
private data class Seen(
    val endpoint: String,
    val form: Map<String, String?>,
    val atMs: Long,
)

private data class Reply(
    val status: HttpStatusCode,
    val body: String,
)

/**
 * A scripted Hardcover: each endpoint answers from its own queue, falling back to a default when the
 * queue is empty (a poll keeps saying `authorization_pending`, a revoke succeeds, the rest fail).
 */
private class FakeHardcover(
    virtualNowMs: () -> Long,
) {
    val seen = CopyOnWriteArrayList<Seen>()
    private val queues = HashMap<String, ArrayDeque<Reply>>()

    /** Runs before a request is answered — lets a test assert what was committed at that moment. */
    @Volatile var beforeAnswer: (suspend (Seen) -> Unit)? = null

    fun enqueue(
        endpoint: String,
        status: HttpStatusCode,
        body: String,
    ) = synchronized(this) { queues.getOrPut(endpoint) { ArrayDeque() }.addLast(Reply(status, body)) }

    fun count(endpoint: String) = seen.count { it.endpoint == endpoint }

    val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { req ->
        val form =
            (req.body as? FormDataContent)?.formData?.let { p -> p.names().associateWith { p[it] } }.orEmpty()
        val endpoint =
            when (req.url.encodedPath) {
                "/oauth2/device" -> DEVICE
                "/oauth2/revoke" -> REVOKE
                "/v1/graphql" -> ME
                else -> if (form["grant_type"] == "refresh_token") REFRESH else POLL
            }
        val request = Seen(endpoint, form, virtualNowMs())
        seen += request
        beforeAnswer?.invoke(request)
        val reply =
            synchronized(this@FakeHardcover) { queues[endpoint]?.removeFirstOrNull() } ?: defaultReply(endpoint)
        respond(reply.body, reply.status, JSON)
    }

    private fun defaultReply(endpoint: String) =
        when (endpoint) {
            POLL -> Reply(HttpStatusCode.BadRequest, oauthError("authorization_pending"))
            REVOKE -> Reply(HttpStatusCode.OK, "{}")
            else -> Reply(HttpStatusCode.InternalServerError, "{}")
        }

    companion object {
        const val DEVICE = "device"
        const val POLL = "poll"
        const val REFRESH = "refresh"
        const val REVOKE = "revoke"
        const val ME = "me"
    }
}

private fun cipherFor(secret: String) = HardcoverTokenCipher(HardcoverTokenCipher.deriveKey(secret))

/** Everything wired over one real SQLite file, a scripted Hardcover, and a clock that reads virtual time. */
private class Rig(
    dbs: SqlTestDatabases,
    val scope: TestScope,
) {
    val clock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs())
        }
    val fake = FakeHardcover(::virtualMs)
    val sql = dbs.sql
    val store = HardcoverConnectionStore(sql, cipherFor("the-real-jwt-secret"), clock)
    private val oauth = HardcoverOAuthClient(HttpClient(MockEngine(fake.handler)), "listenup-test", "https://hc.test")
    private val graphQl = HardcoverGraphQlClient(HttpClient(MockEngine(fake.handler)), "https://hc.test")
    val linker = HardcoverLinker(oauth, graphQl, store, scope.backgroundScope, clock)
    val provider = HardcoverTokenProvider(oauth, store, linker, clock)

    fun virtualMs() = scope.testScheduler.currentTime

    fun nowMs() = BASE_EPOCH_MS + virtualMs()

    /** A healthy connection for [USER] whose access token expires in [accessExpiresIn] seconds. */
    suspend fun seedConnection(
        accessExpiresIn: Long = WEEK_SECONDS,
        viaStore: HardcoverConnectionStore = store,
    ) = viaStore.save(USER, HardcoverMe(42, "simon"), HardcoverTokens("hc_at_1", "hc_rt_1", accessExpiresIn, HARDCOVER_SCOPES))

    suspend fun state(userId: String = USER) = linker.observe(userId).value

    /** Waits (in virtual time) for the sign-in poll to settle into something other than Linking. */
    suspend fun settled(userId: String = USER) = linker.observe(userId).first { it !is HardcoverConnection.Linking }
}

private fun hardcoverTest(block: suspend Rig.() -> Unit) =
    withSqlDatabase {
        sql.seedTestUser(USER)
        sql.seedTestUser(OTHER_USER)
        val dbs = this
        runTest { Rig(dbs, this).block() }
    }

/** The Hardcover sign-in, the encrypted connection row, and the single-flight rotating refresh. */
class HardcoverConnectionsTest :
    FunSpec({

        test("a sign-in polls until Hardcover grants, then stores the tokens and reports Connected") {
            hardcoverTest {
                fake.enqueue(FakeHardcover.DEVICE, HttpStatusCode.OK, deviceJson())
                fake.enqueue(FakeHardcover.POLL, HttpStatusCode.BadRequest, oauthError("authorization_pending"))
                fake.enqueue(FakeHardcover.POLL, HttpStatusCode.OK, tokenJson("hc_at_1", "hc_rt_1"))
                fake.enqueue(FakeHardcover.ME, HttpStatusCode.OK, ME_SIMON)

                val prompt = linker.start(USER).shouldBeInstanceOf<AppResult.Success<*>>().data
                prompt shouldBe
                    HardcoverLinkPrompt(
                        userCode = "ABCD-1234",
                        verificationUri = "https://hardcover.app/link",
                        verificationUriComplete = "https://hardcover.app/link?code=ABCD-1234",
                        expiresAt = BASE_EPOCH_MS + 600_000,
                    )
                state().shouldBeInstanceOf<HardcoverConnection.Linking>()

                val connected = settled().shouldBeInstanceOf<HardcoverConnection.Connected>()
                connected.hardcoverUsername shouldBe "simon"
                fake.seen.filter { it.endpoint == FakeHardcover.POLL }.map { it.atMs } shouldBe listOf(5_000L, 10_000L)
                val credentials = store.connectionFor(USER).shouldBeInstanceOf<StoredConnection.Healthy>().credentials
                credentials.accessToken shouldBe "hc_at_1"
                credentials.refreshToken shouldBe "hc_rt_1"
                store.connectionState(USER) shouldBe connected
                state(OTHER_USER) shouldBe HardcoverConnection.NotConnected()
            }
        }

        test("a completed sign-in announces the connection; a declined one announces nothing") {
            hardcoverTest {
                val announced = mutableListOf<String>()
                scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                    linker.connections.toList(announced)
                }
                fake.enqueue(FakeHardcover.DEVICE, HttpStatusCode.OK, deviceJson())
                fake.enqueue(FakeHardcover.POLL, HttpStatusCode.BadRequest, oauthError("access_denied"))
                linker.start(OTHER_USER).shouldBeInstanceOf<AppResult.Success<*>>()
                settled(OTHER_USER)

                fake.enqueue(FakeHardcover.DEVICE, HttpStatusCode.OK, deviceJson())
                fake.enqueue(FakeHardcover.POLL, HttpStatusCode.OK, tokenJson("hc_at_1", "hc_rt_1"))
                fake.enqueue(FakeHardcover.ME, HttpStatusCode.OK, ME_SIMON)
                linker.start(USER).shouldBeInstanceOf<AppResult.Success<*>>()
                settled().shouldBeInstanceOf<HardcoverConnection.Connected>()

                announced shouldBe listOf(USER)
            }
        }

        test("a declined sign-in ends NotConnected(DENIED) and stores nothing") {
            hardcoverTest {
                fake.enqueue(FakeHardcover.DEVICE, HttpStatusCode.OK, deviceJson())
                fake.enqueue(FakeHardcover.POLL, HttpStatusCode.BadRequest, oauthError("access_denied"))

                linker.start(USER).shouldBeInstanceOf<AppResult.Success<*>>()

                settled() shouldBe HardcoverConnection.NotConnected(HardcoverLinkFailure.DENIED)
                store.connectionFor(USER).shouldBeNull()
            }
        }

        test("a code nobody approves ends NotConnected(EXPIRED) once its deadline passes") {
            hardcoverTest {
                fake.enqueue(FakeHardcover.DEVICE, HttpStatusCode.OK, deviceJson(expiresIn = 12, interval = 5))

                linker.start(USER).shouldBeInstanceOf<AppResult.Success<*>>()

                settled() shouldBe HardcoverConnection.NotConnected(HardcoverLinkFailure.EXPIRED)
                fake.count(FakeHardcover.POLL) shouldBe 2
                store.connectionFor(USER).shouldBeNull()
            }
        }

        test("slow_down adds five seconds to the poll interval") {
            hardcoverTest {
                fake.enqueue(FakeHardcover.DEVICE, HttpStatusCode.OK, deviceJson())
                fake.enqueue(FakeHardcover.POLL, HttpStatusCode.BadRequest, oauthError("slow_down"))
                fake.enqueue(FakeHardcover.POLL, HttpStatusCode.BadRequest, oauthError("authorization_pending"))
                fake.enqueue(FakeHardcover.POLL, HttpStatusCode.BadRequest, oauthError("access_denied"))

                linker.start(USER)
                settled()

                fake.seen.filter { it.endpoint == FakeHardcover.POLL }.map { it.atMs } shouldBe
                    listOf(5_000L, 15_000L, 25_000L)
            }
        }

        test("starting while connected is AlreadyConnected, and never asks Hardcover for a code") {
            hardcoverTest {
                seedConnection()

                linker
                    .start(USER)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<HardcoverError.AlreadyConnected>()
                fake.count(FakeHardcover.DEVICE) shouldBe 0
            }
        }

        test("a broken connection can be relinked, and the new sign-in replaces it") {
            hardcoverTest {
                seedConnection()
                store.markBroken(USER, HardcoverBrokenReason.REVOKED)
                fake.enqueue(FakeHardcover.DEVICE, HttpStatusCode.OK, deviceJson())
                fake.enqueue(FakeHardcover.POLL, HttpStatusCode.OK, tokenJson("hc_at_9", "hc_rt_9"))
                fake.enqueue(FakeHardcover.ME, HttpStatusCode.OK, ME_SIMON)

                linker.start(USER).shouldBeInstanceOf<AppResult.Success<*>>()

                settled().shouldBeInstanceOf<HardcoverConnection.Connected>()
                store
                    .connectionFor(USER)
                    .shouldBeInstanceOf<StoredConnection.Healthy>()
                    .credentials.accessToken shouldBe
                    "hc_at_9"
            }
        }

        test("an unavailable Hardcover at start is HardcoverError.Unavailable") {
            hardcoverTest {
                linker
                    .start(USER)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<HardcoverError.Unavailable>()
                state() shouldBe HardcoverConnection.NotConnected()
            }
        }

        test("a refresh commits the rotated pair before the new access token is handed out") {
            hardcoverTest {
                seedConnection(accessExpiresIn = 300)
                fake.enqueue(FakeHardcover.REFRESH, HttpStatusCode.OK, tokenJson("hc_at_2", "hc_rt_2"))

                provider.accessToken(USER) shouldBe TokenLookup.Valid("hc_at_2")

                // Nothing ran between the refresh and the return but the commit: the row already holds the new pair.
                val credentials = store.connectionFor(USER).shouldBeInstanceOf<StoredConnection.Healthy>().credentials
                credentials.refreshToken shouldBe "hc_rt_2"
                credentials.accessToken shouldBe "hc_at_2"
                fake.seen.single { it.endpoint == FakeHardcover.REFRESH }.form["refresh_token"] shouldBe "hc_rt_1"

                // The next caller sees the committed pair, and the week-long token needs no refresh.
                fake.beforeAnswer = { error("no request expected, saw ${it.endpoint}") }
                provider.accessToken(USER) shouldBe TokenLookup.Valid("hc_at_2")
                fake.count(FakeHardcover.REFRESH) shouldBe 1
            }
        }

        test("a refresh Hardcover refuses marks the connection Broken(REVOKED) everywhere") {
            hardcoverTest {
                seedConnection(accessExpiresIn = 300)
                state().shouldBeInstanceOf<HardcoverConnection.Connected>()
                fake.enqueue(FakeHardcover.REFRESH, HttpStatusCode.BadRequest, oauthError("invalid_grant"))

                provider.accessToken(USER) shouldBe TokenLookup.Broken(HardcoverBrokenReason.REVOKED)

                // The name is plain text in the row, so a revoked connection still says whose it was.
                store.connectionState(USER) shouldBe HardcoverConnection.Broken(HardcoverBrokenReason.REVOKED, "simon")
                state() shouldBe HardcoverConnection.Broken(HardcoverBrokenReason.REVOKED, "simon")
                provider.accessToken(USER) shouldBe TokenLookup.Broken(HardcoverBrokenReason.REVOKED)
                fake.count(FakeHardcover.REFRESH) shouldBe 1
            }
        }

        test("an unreachable refresh keeps using the current token while it is still valid") {
            hardcoverTest {
                seedConnection(accessExpiresIn = 300)
                val before = state()

                provider.accessToken(USER) shouldBe TokenLookup.Valid("hc_at_1")

                state() shouldBe before
                store
                    .connectionFor(USER)
                    .shouldBeInstanceOf<StoredConnection.Healthy>()
                    .credentials.refreshToken shouldBe
                    "hc_rt_1"

                // Once the current token has actually expired there is nothing valid to hand out.
                scope.advanceTimeBy(301_000)
                provider.accessToken(USER) shouldBe TokenLookup.Unavailable
            }
        }

        test("concurrent callers near expiry share exactly one refresh") {
            hardcoverTest {
                seedConnection(accessExpiresIn = 300)
                fake.enqueue(FakeHardcover.REFRESH, HttpStatusCode.OK, tokenJson("hc_at_2", "hc_rt_2"))

                val lookups = List(2) { async { provider.accessToken(USER) } }.awaitAll()

                lookups shouldBe List(2) { TokenLookup.Valid("hc_at_2") }
                fake.count(FakeHardcover.REFRESH) shouldBe 1
            }
        }

        test("a row sealed under another server's secret reports Broken(CANNOT_DECRYPT)") {
            hardcoverTest {
                seedConnection(viaStore = HardcoverConnectionStore(sql, cipherFor("a-different-secret"), clock))

                // Only the tokens are sealed: the username still reads under the wrong secret.
                store.connectionState(USER) shouldBe
                    HardcoverConnection.Broken(HardcoverBrokenReason.CANNOT_DECRYPT, "simon")
                state() shouldBe HardcoverConnection.Broken(HardcoverBrokenReason.CANNOT_DECRYPT, "simon")
                provider.accessToken(USER) shouldBe TokenLookup.Broken(HardcoverBrokenReason.CANNOT_DECRYPT)
                fake.seen.shouldBeEmpty()
            }
        }

        test("disconnect revokes the refresh then the access token, deletes the row, and is idempotent") {
            hardcoverTest {
                seedConnection()
                state().shouldBeInstanceOf<HardcoverConnection.Connected>()

                linker.disconnect(USER)

                fake.seen.filter { it.endpoint == FakeHardcover.REVOKE }.map { it.form["token"] } shouldBe
                    listOf("hc_rt_1", "hc_at_1")
                store.connectionFor(USER).shouldBeNull()
                state() shouldBe HardcoverConnection.NotConnected()
                provider.accessToken(USER) shouldBe TokenLookup.NotConnected

                linker.disconnect(USER)
                state() shouldBe HardcoverConnection.NotConnected()
                fake.count(FakeHardcover.REVOKE) shouldBe 2
            }
        }

        test("disconnect still deletes the row when Hardcover fails the revoke") {
            hardcoverTest {
                seedConnection()
                fake.enqueue(FakeHardcover.REVOKE, HttpStatusCode.InternalServerError, "{}")
                fake.enqueue(FakeHardcover.REVOKE, HttpStatusCode.InternalServerError, "{}")

                linker.disconnect(USER)

                fake.count(FakeHardcover.REVOKE) shouldBe 2
                store.connectionFor(USER).shouldBeNull()
                state() shouldBe HardcoverConnection.NotConnected()
            }
        }

        test("disconnect during a sign-in cancels the poll") {
            hardcoverTest {
                fake.enqueue(FakeHardcover.DEVICE, HttpStatusCode.OK, deviceJson())
                linker.start(USER).shouldBeInstanceOf<AppResult.Success<*>>()

                linker.disconnect(USER)
                scope.advanceTimeBy(60_000)

                fake.count(FakeHardcover.POLL) shouldBe 0
                state() shouldBe HardcoverConnection.NotConnected()
            }
        }

        test("a disconnect that arrives mid-refresh waits for it, then revokes the rotated pair") {
            hardcoverTest {
                seedConnection(accessExpiresIn = 300)
                fake.enqueue(FakeHardcover.REFRESH, HttpStatusCode.OK, tokenJson("hc_at_2", "hc_rt_2"))
                val refreshInFlight = CompletableDeferred<Unit>()
                val releaseRefresh = CompletableDeferred<Unit>()
                fake.beforeAnswer = {
                    if (it.endpoint == FakeHardcover.REFRESH) {
                        refreshInFlight.complete(Unit)
                        releaseRefresh.await()
                    }
                }

                val lookup = async { provider.accessToken(USER) }
                refreshInFlight.await()
                val disconnecting = async { linker.disconnect(USER) }
                // Give an unserialized disconnect (real SQLite and HTTP threads) time to run to completion
                // while Hardcover still holds the refresh; a serialized one stays parked on the user's lock.
                withContext(Dispatchers.Default) { withTimeoutOrNull(500) { disconnecting.join() } }
                releaseRefresh.complete(Unit)
                lookup.await()
                disconnecting.await()

                fake.seen.filter { it.endpoint == FakeHardcover.REVOKE }.map { it.form["token"] } shouldBe
                    listOf("hc_rt_2", "hc_at_2")
                store.connectionFor(USER).shouldBeNull()
                state() shouldBe HardcoverConnection.NotConnected()
            }
        }

        test("a refresh after a disconnect finds no connection and never calls Hardcover") {
            hardcoverTest {
                seedConnection(accessExpiresIn = 300)
                linker.disconnect(USER)

                provider.accessToken(USER) shouldBe TokenLookup.NotConnected
                fake.count(FakeHardcover.REFRESH) shouldBe 0
            }
        }

        test("a grant whose owner can't be looked up is revoked, and nothing is stored") {
            hardcoverTest {
                fake.enqueue(FakeHardcover.DEVICE, HttpStatusCode.OK, deviceJson())
                fake.enqueue(FakeHardcover.POLL, HttpStatusCode.OK, tokenJson("hc_at_1", "hc_rt_1"))
                fake.enqueue(FakeHardcover.ME, HttpStatusCode.InternalServerError, "{}")

                linker.start(USER).shouldBeInstanceOf<AppResult.Success<*>>()

                settled() shouldBe HardcoverConnection.NotConnected(HardcoverLinkFailure.UNREACHABLE)
                fake.seen.filter { it.endpoint == FakeHardcover.REVOKE }.map { it.form["token"] } shouldBe
                    listOf("hc_rt_1", "hc_at_1")
                store.connectionFor(USER).shouldBeNull()
            }
        }
    })
