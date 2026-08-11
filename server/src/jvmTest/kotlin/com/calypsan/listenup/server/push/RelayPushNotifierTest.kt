@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.push

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/**
 * Tests for [RelayPushNotifier] against a real migrated in-memory SQLite database (via
 * [withSqlDatabase]) and a fake relay wire ([io.ktor.client.engine.mock.MockEngine]) — no mocks
 * of [RelayPushNotifier]'s own collaborators. Covers fan-out, the admin toggle, the
 * empty-token no-op, invalid-token eviction, the single batched retry on a `retryable`
 * verdict, and the silent-drop-after-one-retry behaviour when the relay is unreachable.
 */
class RelayPushNotifierTest :
    FunSpec({

        val fixedNow = Instant.fromEpochMilliseconds(1_730_000_000_000L)

        /** Inserts a `sessions` row so `push_tokens.session_id` FK-resolves; `revokedAt` toggles liveness. */
        fun ListenUpDatabase.seedTestSession(
            sessionId: String,
            userId: String,
            revokedAt: Long? = null,
            expiresAt: Long = fixedNow.toEpochMilliseconds() + 1_000_000L,
        ) {
            sessionsQueries.insert(
                id = sessionId,
                user_id = userId,
                refresh_token_hash = "hash-$sessionId",
                family_id = "family-$sessionId",
                previous_hash = null,
                label = null,
                device_type = null,
                platform = null,
                platform_version = null,
                client_name = null,
                client_version = null,
                device_name = null,
                device_model = null,
                user_agent = null,
                created_at = 1L,
                expires_at = expiresAt,
                last_used_at = 1L,
                revoked_at = revokedAt,
            )
        }

        /** Inserts a `push_tokens` row bound to [sessionId]. */
        fun ListenUpDatabase.seedTestToken(
            token: String,
            platform: String,
            sessionId: String,
            userId: String,
        ) {
            pushTokensQueries.upsert(
                token = token,
                platform = platform,
                session_id = sessionId,
                user_id = userId,
                now = fixedNow.toEpochMilliseconds(),
            )
        }

        fun enabledSettings(sql: ListenUpDatabase): ServerSettingsRepository =
            ServerSettingsRepository(
                sql = sql,
                default = RegistrationPolicy.APPROVAL_QUEUE,
            )

        /** Recorded requests + a canned response queue, backing a [MockEngine]. */
        class RecordingEngine(
            private val responses: MutableList<suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>,
        ) {
            val requests = mutableListOf<HttpRequestData>()

            fun bodyJson(request: HttpRequestData): JsonObject {
                val content = request.body as OutgoingContent.ByteArrayContent
                return Json.parseToJsonElement(String(content.bytes())).jsonObject
            }

            fun engine(): MockEngine =
                MockEngine { request ->
                    requests += request
                    val next = if (responses.size == 1) responses.first() else responses.removeAt(0)
                    next(request)
                }
        }

        fun jsonResponse(body: String): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }

        fun failure(): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { throw IOException("relay unreachable") }

        fun relayClientFor(engine: MockEngine): PushRelayClient {
            val http = HttpClient(engine) { install(ContentNegotiation) { json(contractJson) } }
            return PushRelayClient(relayUrl = "https://relay.example.com", http = http)
        }

        test("fans out to all live tokens in one relay call") {
            withSqlDatabase {
                sql.seedTestUser("alice")
                sql.seedTestSession("live-1", "alice")
                sql.seedTestSession("live-2", "alice")
                sql.seedTestSession("revoked", "alice", revokedAt = fixedNow.toEpochMilliseconds())
                sql.seedTestToken("token-android", "ANDROID", "live-1", "alice")
                sql.seedTestToken("token-ios", "IOS", "live-2", "alice")
                sql.seedTestToken("token-revoked", "ANDROID", "revoked", "alice")

                val recorder =
                    RecordingEngine(
                        mutableListOf(
                            jsonResponse(
                                """{"results":[{"token":"token-android","status":"ok"},{"token":"token-ios","status":"ok"}]}""",
                            ),
                        ),
                    )
                val notifier =
                    RelayPushNotifier(
                        db = sql,
                        relay = relayClientFor(recorder.engine()),
                        settings = enabledSettings(sql),
                        clock = FixedClock(fixedNow),
                    )
                val payload = PushPayload.TestNotification(sentAtMs = fixedNow.toEpochMilliseconds())

                runTest {
                    notifier.notify("alice", payload)
                }

                recorder.requests shouldHaveSize 1
                val body = recorder.bodyJson(recorder.requests.single())
                val tokens = body["tokens"]!!.jsonArray
                tokens shouldHaveSize 2
                val sentTokens =
                    tokens
                        .map {
                            it.jsonObject["token"]!!.jsonPrimitive.content to
                                it.jsonObject["platform"]!!.jsonPrimitive.content
                        }.toSet()
                // The relay wire protocol speaks lowercase platform tags (validate.ts:
                // "platform must be android|ios"); rows store the PushPlatform enum name.
                sentTokens shouldBe setOf("token-android" to "android", "token-ios" to "ios")
                body["payload"] shouldBe contractJson.encodeToJsonElement(PushPayload.serializer(), payload)
            }
        }

        test("admin toggle off → no relay call") {
            withSqlDatabase {
                sql.seedTestUser("alice")
                sql.seedTestSession("live-1", "alice")
                sql.seedTestToken("token-android", "ANDROID", "live-1", "alice")

                val settings = enabledSettings(sql)
                val recorder = RecordingEngine(mutableListOf(jsonResponse("""{"results":[]}""")))
                val notifier =
                    RelayPushNotifier(
                        db = sql,
                        relay = relayClientFor(recorder.engine()),
                        settings = settings,
                        clock = FixedClock(fixedNow),
                    )

                runTest {
                    settings.setPushNotificationsEnabled(false)
                    notifier.notify("alice", PushPayload.TestNotification(sentAtMs = fixedNow.toEpochMilliseconds()))
                }

                recorder.requests.shouldHaveSize(0)
            }
        }

        test("no live tokens → no relay call") {
            withSqlDatabase {
                sql.seedTestUser("alice")
                sql.seedTestSession("revoked", "alice", revokedAt = fixedNow.toEpochMilliseconds())
                sql.seedTestToken("token-android", "ANDROID", "revoked", "alice")

                val recorder = RecordingEngine(mutableListOf(jsonResponse("""{"results":[]}""")))
                val notifier =
                    RelayPushNotifier(
                        db = sql,
                        relay = relayClientFor(recorder.engine()),
                        settings = enabledSettings(sql),
                        clock = FixedClock(fixedNow),
                    )

                runTest {
                    notifier.notify("alice", PushPayload.TestNotification(sentAtMs = fixedNow.toEpochMilliseconds()))
                }

                recorder.requests.shouldHaveSize(0)
            }
        }

        test("invalid verdict deletes that token row only") {
            withSqlDatabase {
                sql.seedTestUser("alice")
                sql.seedTestSession("live-1", "alice")
                sql.seedTestSession("live-2", "alice")
                sql.seedTestToken("token-bad", "ANDROID", "live-1", "alice")
                sql.seedTestToken("token-good", "IOS", "live-2", "alice")

                val recorder =
                    RecordingEngine(
                        mutableListOf(
                            jsonResponse(
                                """{"results":[{"token":"token-bad","status":"invalid"},{"token":"token-good","status":"ok"}]}""",
                            ),
                        ),
                    )
                val notifier =
                    RelayPushNotifier(
                        db = sql,
                        relay = relayClientFor(recorder.engine()),
                        settings = enabledSettings(sql),
                        clock = FixedClock(fixedNow),
                    )

                runTest {
                    notifier.notify("alice", PushPayload.TestNotification(sentAtMs = fixedNow.toEpochMilliseconds()))
                }

                sql.pushTokensQueries.countAll().executeAsOne() shouldBe 1L
                val remaining = sql.pushTokensQueries.selectLiveForUser("alice", fixedNow.toEpochMilliseconds()).executeAsList()
                remaining shouldHaveSize 1
                remaining.first().token shouldBe "token-good"
            }
        }

        test("retryable verdict retries once as a batch, then accepts the outcome") {
            withSqlDatabase {
                sql.seedTestUser("alice")
                sql.seedTestSession("live-1", "alice")
                sql.seedTestToken("token-flaky", "ANDROID", "live-1", "alice")

                val recorder =
                    RecordingEngine(
                        mutableListOf(
                            jsonResponse("""{"results":[{"token":"token-flaky","status":"retryable"}]}"""),
                            jsonResponse("""{"results":[{"token":"token-flaky","status":"retryable"}]}"""),
                        ),
                    )
                val notifier =
                    RelayPushNotifier(
                        db = sql,
                        relay = relayClientFor(recorder.engine()),
                        settings = enabledSettings(sql),
                        clock = FixedClock(fixedNow),
                    )

                runTest {
                    notifier.notify("alice", PushPayload.TestNotification(sentAtMs = fixedNow.toEpochMilliseconds()))
                }

                recorder.requests shouldHaveSize 2
                // Retryable outcome is accepted as-is on the second attempt — no deletion, no further retry.
                sql.pushTokensQueries.countAll().executeAsOne() shouldBe 1L
            }
        }

        test("relay unreachable → one retry → silent drop, never throws") {
            withSqlDatabase {
                sql.seedTestUser("alice")
                sql.seedTestSession("live-1", "alice")
                sql.seedTestToken("token-android", "ANDROID", "live-1", "alice")

                val recorder = RecordingEngine(mutableListOf(failure(), failure()))
                val notifier =
                    RelayPushNotifier(
                        db = sql,
                        relay = relayClientFor(recorder.engine()),
                        settings = enabledSettings(sql),
                        clock = FixedClock(fixedNow),
                    )

                runTest {
                    // Must not throw — best-effort by design.
                    notifier.notify("alice", PushPayload.TestNotification(sentAtMs = fixedNow.toEpochMilliseconds()))
                }

                recorder.requests shouldHaveSize 2
                sql.pushTokensQueries.countAll().executeAsOne() shouldBe 1L
            }
        }
    })
