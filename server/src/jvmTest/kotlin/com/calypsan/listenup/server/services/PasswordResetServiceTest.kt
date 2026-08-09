@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.auth.PasswordResetTicket
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PepperedHasher
import com.calypsan.listenup.server.auth.ResetCodeGenerator
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class PasswordResetServiceTest :
    FunSpec({
        val pepper = ByteArray(32) { it.toByte() }
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

        fun service(
            db: ListenUpDatabase,
            at: Instant = now,
        ) = PasswordResetService(
            db = db,
            hasher = PepperedHasher(pepper),
            codes = ResetCodeGenerator(),
            clock = FixedClock(at),
        )

        test("a known address creates a PENDING request") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                runTest {
                    val result = service(sql).request("ada@example.com", deviceClaim = "claim-1")

                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                    val stored =
                        sql.passwordResetRequestsQueries
                            .selectPending(now.toEpochMilliseconds())
                            .executeAsList()
                    stored.size shouldBe 1
                    stored.single().status shouldBe "PENDING"
                }
            }
        }

        test("an unknown address returns a ticket but persists nothing — no existence oracle") {
            withSqlDatabase {
                runTest {
                    val result = service(sql).request("nobody@example.com", deviceClaim = "claim-1")

                    val success = result.shouldBeInstanceOf<AppResult.Success<PasswordResetTicket>>()
                    success.data.ticketId.isNotBlank() shouldBe true
                    sql.passwordResetRequestsQueries
                        .selectPending(now.toEpochMilliseconds())
                        .executeAsList()
                        .size shouldBe 0
                }
            }
        }

        test("known and unknown addresses return structurally identical tickets") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                val svc = service(sql)
                runTest {
                    val known =
                        (svc.request("ada@example.com", "c") as AppResult.Success<PasswordResetTicket>).data
                    val unknown =
                        (svc.request("nobody@example.com", "c") as AppResult.Success<PasswordResetTicket>).data

                    // Same expiry, both non-blank opaque ids, and the ids must DIFFER
                    // (a constant or empty id for the unknown case is itself a tell).
                    known.expiresAt shouldBe unknown.expiresAt
                    known.ticketId shouldNotBe unknown.ticketId
                    unknown.ticketId.isNotBlank() shouldBe true
                }
            }
        }

        test("the device claim is stored hashed and domain-tagged, never in the clear") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                runTest {
                    service(sql).request("ada@example.com", deviceClaim = "claim-1")

                    val stored =
                        sql.passwordResetRequestsQueries
                            .selectPending(now.toEpochMilliseconds())
                            .executeAsOne()

                    stored.device_claim_hash shouldNotBe "claim-1"
                    stored.device_claim_hash shouldBe
                        PepperedHasher(pepper).hash(PasswordResetService.CLAIM_DOMAIN + "claim-1")
                    // The domain tag must genuinely be applied — an untagged hash must NOT
                    // match, or the separation is decorative.
                    stored.device_claim_hash shouldNotBe PepperedHasher(pepper).hash("claim-1")
                }
            }
        }

        test("a second request supersedes the first rather than accumulating") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                val svc = service(sql)
                runTest {
                    svc.request("ada@example.com", deviceClaim = "claim-1")
                    svc.request("ada@example.com", deviceClaim = "claim-2")

                    sql.passwordResetRequestsQueries
                        .selectPending(now.toEpochMilliseconds())
                        .executeAsList()
                        .size shouldBe 1
                }
            }
        }

        test("a fresh request has no code — there is nothing to guess before approval") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                runTest {
                    service(sql).request("ada@example.com", deviceClaim = "claim-1")

                    sql.passwordResetRequestsQueries
                        .selectPending(now.toEpochMilliseconds())
                        .executeAsOne()
                        .code_hash shouldBe null
                }
            }
        }
    })
