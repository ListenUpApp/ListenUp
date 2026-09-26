package com.calypsan.listenup.server.api

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.hardcover.HARDCOVER_SCOPES
import com.calypsan.listenup.server.hardcover.HardcoverConnectionStore
import com.calypsan.listenup.server.hardcover.HardcoverGraphQlClient
import com.calypsan.listenup.server.hardcover.HardcoverLinker
import com.calypsan.listenup.server.hardcover.HardcoverMe
import com.calypsan.listenup.server.hardcover.HardcoverOAuthClient
import com.calypsan.listenup.server.hardcover.HardcoverTokenCipher
import com.calypsan.listenup.server.hardcover.HardcoverTokens
import com.calypsan.listenup.server.testing.SqlTestDatabases
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
    val store =
        HardcoverConnectionStore(dbs.sql, HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("a-jwt-secret")))
    private val linker =
        HardcoverLinker(
            HardcoverOAuthClient(hardcover.client, "listenup-test", "https://hc.test"),
            HardcoverGraphQlClient(hardcover.client, "https://hc.test"),
            store,
            scope.backgroundScope,
        )
    val unscoped = HardcoverServiceImpl(linker, clientIdConfigured)

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
                hardcover.paths shouldBe emptyList()
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
    })
