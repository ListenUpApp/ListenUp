package com.calypsan.listenup.server.hardcover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.IOException

private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")

private const val DEVICE_JSON =
    """{"device_code":"dev-1","user_code":"ABCD-1234","verification_uri":"https://hardcover.app/link",""" +
        """"verification_uri_complete":"https://hardcover.app/link?code=ABCD-1234",""" +
        """"expires_in":600,"interval":5}"""

private const val TOKEN_JSON =
    """{"access_token":"hc_at_1","refresh_token":"hc_rt_1","expires_in":604800,""" +
        """"scope":"read:me:content write:library","token_type":"Bearer"}"""

private fun oauthClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
    HardcoverOAuthClient(
        http = HttpClient(MockEngine(handler)),
        clientId = "listenup-test",
        apiBaseUrl = "https://hc.test",
    )

private val HttpRequestData.form: Parameters get() = (body as FormDataContent).formData

/** The OAuth client speaks RFC 8628 (device grant) and RFC 6749 (refresh/revoke) exactly as Hardcover answers them. */
class HardcoverOAuthClientTest :
    FunSpec({

        test("startDeviceAuthorization posts client_id and the four scopes and returns the prompt") {
            runTest {
                var sent: HttpRequestData? = null
                val client =
                    oauthClient { req ->
                        sent = req
                        respond(
                            DEVICE_JSON,
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    }

                val auth =
                    client
                        .startDeviceAuthorization()
                        .shouldBeInstanceOf<DeviceAuthorizationResult.Started>()
                        .authorization

                val req = sent!!
                req.method shouldBe HttpMethod.Post
                req.url.toString() shouldBe "https://hc.test/oauth2/device"
                req.form["client_id"] shouldBe "listenup-test"
                req.form["scope"]!!.split(" ").toSet() shouldBe
                    setOf("read:me:content", "read:catalog", "read:library", "write:library")
                auth.deviceCode shouldBe "dev-1"
                auth.userCode shouldBe "ABCD-1234"
                auth.verificationUri shouldBe "https://hardcover.app/link"
                auth.verificationUriComplete shouldBe "https://hardcover.app/link?code=ABCD-1234"
                auth.expiresInSeconds shouldBe 600
                auth.intervalSeconds shouldBe 5
            }
        }

        test("startDeviceAuthorization defaults the interval to 5 seconds and the complete URI to the plain one") {
            runTest {
                val client =
                    oauthClient {
                        respond(
                            """{"device_code":"d","user_code":"U","verification_uri":"https://hardcover.app/link","expires_in":600}""",
                            HttpStatusCode.OK,
                            JSON_HEADERS,
                        )
                    }
                val auth =
                    client
                        .startDeviceAuthorization()
                        .shouldBeInstanceOf<DeviceAuthorizationResult.Started>()
                        .authorization
                auth.intervalSeconds shouldBe 5
                auth.verificationUriComplete shouldBe "https://hardcover.app/link"
            }
        }

        test("every request identifies itself with a ListenUp User-Agent") {
            runTest {
                val agents = mutableListOf<String?>()
                val client =
                    oauthClient { req ->
                        agents += req.headers[HttpHeaders.UserAgent]
                        respond("""{"error":"authorization_pending"}""", HttpStatusCode.BadRequest, JSON_HEADERS)
                    }
                client.startDeviceAuthorization()
                client.pollToken("dev-1")
                client.refresh("hc_rt_1")
                client.revoke("hc_at_1")

                agents.size shouldBe 4
                agents.forEach { it!! shouldStartWith "ListenUp" }
            }
        }

        mapOf(
            "authorization_pending" to TokenPoll.Pending::class,
            "slow_down" to TokenPoll.SlowDown::class,
            "access_denied" to TokenPoll.Denied::class,
            "expired_token" to TokenPoll.Expired::class,
        ).forEach { (error, expected) ->
            test("pollToken maps $error") {
                runTest {
                    val client = oauthClient { respond("""{"error":"$error"}""", HttpStatusCode.BadRequest, JSON_HEADERS) }
                    client.pollToken("dev-1")::class shouldBe expected
                }
            }
        }

        test("pollToken posts the device_code grant and returns the granted token pair") {
            runTest {
                var sent: HttpRequestData? = null
                val client =
                    oauthClient { req ->
                        sent = req
                        respond(TOKEN_JSON, HttpStatusCode.OK, JSON_HEADERS)
                    }

                val tokens = client.pollToken("dev-1").shouldBeInstanceOf<TokenPoll.Granted>().tokens

                val req = sent!!
                req.url.toString() shouldBe "https://hc.test/oauth2/token"
                req.form["grant_type"] shouldBe "urn:ietf:params:oauth:grant-type:device_code"
                req.form["device_code"] shouldBe "dev-1"
                req.form["client_id"] shouldBe "listenup-test"
                tokens.accessToken shouldBe "hc_at_1"
                tokens.refreshToken shouldBe "hc_rt_1"
                tokens.expiresInSeconds shouldBe 604_800L
                tokens.scope shouldBe "read:me:content write:library"
            }
        }

        test("refresh posts the refresh_token grant and returns the rotated pair") {
            runTest {
                var sent: HttpRequestData? = null
                val client =
                    oauthClient { req ->
                        sent = req
                        respond(TOKEN_JSON, HttpStatusCode.OK, JSON_HEADERS)
                    }

                val tokens = client.refresh("hc_rt_old").shouldBeInstanceOf<RefreshResult.Granted>().tokens

                val req = sent!!
                req.url.toString() shouldBe "https://hc.test/oauth2/token"
                req.form["grant_type"] shouldBe "refresh_token"
                req.form["refresh_token"] shouldBe "hc_rt_old"
                req.form["client_id"] shouldBe "listenup-test"
                tokens.refreshToken shouldBe "hc_rt_1"
            }
        }

        test("refresh maps invalid_grant to InvalidGrant (a spent or revoked chain)") {
            runTest {
                val client = oauthClient { respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest, JSON_HEADERS) }
                client.refresh("hc_rt_old").shouldBeInstanceOf<RefreshResult.InvalidGrant>()
            }
        }

        test("revoke posts the token to /oauth2/revoke and reports the acknowledgement") {
            runTest {
                var sent: HttpRequestData? = null
                val client =
                    oauthClient { req ->
                        sent = req
                        respond("", HttpStatusCode.OK)
                    }

                client.revoke("hc_rt_1") shouldBe true

                val req = sent!!
                req.method shouldBe HttpMethod.Post
                req.url.toString() shouldBe "https://hc.test/oauth2/revoke"
                req.form["token"] shouldBe "hc_rt_1"
                req.form["client_id"] shouldBe "listenup-test"
            }
        }

        test("a 5xx Hardcover is Unavailable, not a crash") {
            runTest {
                val client = oauthClient { respond("oops", HttpStatusCode.ServiceUnavailable) }
                client.startDeviceAuthorization().shouldBeInstanceOf<DeviceAuthorizationResult.Unavailable>()
                client.pollToken("dev-1").shouldBeInstanceOf<TokenPoll.Unavailable>()
                client.refresh("hc_rt_old").shouldBeInstanceOf<RefreshResult.Unavailable>()
                client.revoke("hc_rt_old") shouldBe false
            }
        }

        test("an unreachable Hardcover is Unavailable, not a crash") {
            runTest {
                val client = oauthClient { throw IOException("connection refused") }
                client.startDeviceAuthorization().shouldBeInstanceOf<DeviceAuthorizationResult.Unavailable>()
                client.pollToken("dev-1").shouldBeInstanceOf<TokenPoll.Unavailable>()
                client.refresh("hc_rt_old").shouldBeInstanceOf<RefreshResult.Unavailable>()
                client.revoke("hc_rt_old") shouldBe false
            }
        }
    })
