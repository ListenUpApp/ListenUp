@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.PasswordResetDecisionOutcome
import com.calypsan.listenup.api.dto.auth.PasswordResetStatus
import com.calypsan.listenup.api.dto.auth.PasswordResetStatusEvent
import com.calypsan.listenup.api.dto.auth.PasswordResetTicket
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.PepperedHasher
import com.calypsan.listenup.server.auth.ResetCodeGenerator
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Pins the anti-oracle property on [PasswordResetService.observeStatus]: a ticket id with no
 * backing row must be **indistinguishable** from a real request nobody has approved yet — never
 * an error, never a premature terminal read. See the KDoc on
 * [com.calypsan.listenup.api.AuthServicePublic.observePasswordResetStatus] for the full
 * rationale (the request() side of this guarantee is pinned in [PasswordResetServiceTest]).
 */
class PasswordResetStatusStreamTest :
    FunSpec({
        val pepper = ByteArray(32) { it.toByte() }
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

        fun service(
            db: ListenUpDatabase,
            clock: MutableClock,
        ) = PasswordResetService(
            db = db,
            hasher = PepperedHasher(pepper),
            codes = ResetCodeGenerator(),
            clock = clock,
            sessions = SessionRevoker { },
            passwords = Argon2Limiter(PasswordHasher()),
        )

        test("an unknown ticket emits PENDING, exactly as a real unapproved request does") {
            withSqlDatabase {
                runTest {
                    val svc = service(sql, MutableClock(now))

                    svc.observeStatus(Uuid.random().toString()).test {
                        awaitItem().status shouldBe PasswordResetStatus.PENDING
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            }
        }

        test("a real pending request and an unknown ticket are indistinguishable on first emission") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                runTest {
                    val svc = service(sql, MutableClock(now))
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success<PasswordResetTicket>).data

                    lateinit var knownEvent: PasswordResetStatusEvent
                    svc.observeStatus(ticket.ticketId).test {
                        knownEvent = awaitItem()
                        cancelAndIgnoreRemainingEvents()
                    }
                    lateinit var unknownEvent: PasswordResetStatusEvent
                    svc.observeStatus(Uuid.random().toString()).test {
                        unknownEvent = awaitItem()
                        cancelAndIgnoreRemainingEvents()
                    }

                    // The whole point of this test: nothing distinguishes a real pending ticket
                    // from a fabricated one on the wire.
                    knownEvent shouldBe unknownEvent
                }
            }
        }

        test("an unknown ticket completes as EXPIRED rather than erroring") {
            withSqlDatabase {
                runTest {
                    val clock = MutableClock(now)
                    val svc = service(sql, clock)

                    svc.observeStatus(Uuid.random().toString()).test {
                        awaitItem().status shouldBe PasswordResetStatus.PENDING

                        // Advance past the TTL the phantom expiry was minted against.
                        clock.instant = now + 15.minutes + 1.minutes

                        awaitItem().status shouldBe PasswordResetStatus.EXPIRED
                        awaitComplete()
                    }
                }
            }
        }

        test("a denied request completes the stream") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                runTest {
                    val svc = service(sql, MutableClock(now))
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success<PasswordResetTicket>).data
                    svc.decide(ticket.ticketId, approved = false, adminId = "admin-1")

                    svc.observeStatus(ticket.ticketId).test {
                        awaitItem().status shouldBe PasswordResetStatus.DENIED
                        awaitComplete()
                    }
                }
            }
        }

        test("a consumed request completes the stream") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                runTest {
                    val svc = service(sql, MutableClock(now))
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success<PasswordResetTicket>).data
                    val code =
                        (
                            (svc.decide(ticket.ticketId, true, "admin-1") as AppResult.Success).data
                                as PasswordResetDecisionOutcome.Approved
                        ).code
                    svc.complete(ticket.ticketId, "claim-1", code, "a-strong-new-password")

                    svc.observeStatus(ticket.ticketId).test {
                        awaitItem().status shouldBe PasswordResetStatus.CONSUMED
                        awaitComplete()
                    }
                }
            }
        }

        test("an approved request does NOT complete — the client still needs to enter the code") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                runTest {
                    val svc = service(sql, MutableClock(now))
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success<PasswordResetTicket>).data
                    svc.decide(ticket.ticketId, approved = true, adminId = "admin-1")

                    svc.observeStatus(ticket.ticketId).test {
                        awaitItem().status shouldBe PasswordResetStatus.APPROVED
                        // A second poll cycle re-confirms APPROVED rather than completing —
                        // proves the stream is genuinely still open, not coincidentally quiet.
                        awaitItem().status shouldBe PasswordResetStatus.APPROVED
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            }
        }
    })
