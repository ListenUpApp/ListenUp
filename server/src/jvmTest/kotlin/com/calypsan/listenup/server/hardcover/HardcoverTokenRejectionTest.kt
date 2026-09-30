package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
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
import java.util.concurrent.atomic.AtomicInteger

private const val ROTATED = """{"access_token":"hc_at_2","refresh_token":"hc_rt_2","expires_in":604800,"scope":"$HARDCOVER_SCOPES"}"""

/** A rejected token earns exactly one refresh; a replayed rejection reuses the rotated token. */
class HardcoverTokenRejectionTest :
    FunSpec({

        fun rig(
            sql: ListenUpDatabase,
            status: HttpStatusCode,
            body: String,
            refreshes: AtomicInteger,
        ): Pair<HardcoverTokenProvider, HardcoverConnectionStore> {
            val store = HardcoverConnectionStore(sql, HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("secret")))
            val oauth =
                HardcoverOAuthClient(
                    HttpClient(
                        MockEngine {
                            refreshes.incrementAndGet()
                            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    ),
                    "listenup-test",
                    "https://hc.test",
                )
            val linker = HardcoverLinker(oauth, FakeHardcoverLibrary().client(), store, CoroutineScope(Dispatchers.Unconfined))
            return HardcoverTokenProvider(oauth, store, linker) to store
        }

        test("a rejected token is refreshed once; asking again with the old token reuses the new one") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val refreshes = AtomicInteger()
                val (tokens, store) = rig(sql, HttpStatusCode.OK, ROTATED, refreshes)
                runTest {
                    store.save("u1", HardcoverMe(42, "reader"), HardcoverTokens("hc_at_1", "hc_rt_1", 604_800, HARDCOVER_SCOPES))
                    tokens.refreshAfterRejection("u1", "hc_at_1") shouldBe TokenLookup.Valid("hc_at_2")
                    tokens.refreshAfterRejection("u1", "hc_at_1") shouldBe TokenLookup.Valid("hc_at_2")
                    refreshes.get() shouldBe 1
                }
            }
        }

        test("a refused refresh breaks the connection as REVOKED") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val (tokens, store) = rig(sql, HttpStatusCode.BadRequest, """{"error":"invalid_grant"}""", AtomicInteger())
                runTest {
                    store.save("u1", HardcoverMe(42, "reader"), HardcoverTokens("hc_at_1", "hc_rt_1", 604_800, HARDCOVER_SCOPES))
                    tokens.refreshAfterRejection("u1", "hc_at_1") shouldBe TokenLookup.Broken(HardcoverBrokenReason.REVOKED)
                    store.connectionFor("u1").shouldBeInstanceOf<StoredConnection.Broken>().reason shouldBe HardcoverBrokenReason.REVOKED
                }
            }
        }

        test("an unreachable Hardcover leaves a rejected token unusable, not falsely valid") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                val (tokens, store) = rig(sql, HttpStatusCode.ServiceUnavailable, "{}", AtomicInteger())
                runTest {
                    store.save("u1", HardcoverMe(42, "reader"), HardcoverTokens("hc_at_1", "hc_rt_1", 604_800, HARDCOVER_SCOPES))
                    tokens.refreshAfterRejection("u1", "hc_at_1") shouldBe TokenLookup.Unavailable
                }
            }
        }
    })
