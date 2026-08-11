@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.auth.PasswordResetDecisionOutcome
import com.calypsan.listenup.api.dto.auth.PasswordResetTicket
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.PepperedHasher
import com.calypsan.listenup.server.auth.ResetCodeGenerator
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

class PasswordResetServiceTest :
    FunSpec({
        val pepper = ByteArray(32) { it.toByte() }
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

        fun service(
            db: ListenUpDatabase,
            at: Instant = now,
            sessions: SessionRevoker = RecordingRevoker(),
            passwords: Argon2Limiter = Argon2Limiter(PasswordHasher()),
        ) = PasswordResetService(
            db = db,
            hasher = PepperedHasher(pepper),
            codes = ResetCodeGenerator(),
            clock = FixedClock(at),
            sessions = sessions,
            passwords = passwords,
        )

        suspend fun approvedCode(
            svc: PasswordResetService,
            ticket: PasswordResetTicket,
        ): String =
            (
                (svc.decide(ticket.ticketId, true, "admin-1") as AppResult.Success).data
                    as PasswordResetDecisionOutcome.Approved
            ).code

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

        test("approval returns a code and stores only its hash") {
            withSqlDatabase {
                runTest {
                    sql.seedTestUser("ada")
                    val svc = service(sql)
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success).data

                    val outcome = svc.decide(ticket.ticketId, approved = true, adminId = "admin-1")

                    val code =
                        (
                            (outcome as AppResult.Success).data
                                as PasswordResetDecisionOutcome.Approved
                        ).code
                    val stored = sql.passwordResetRequestsQueries.selectById(svc.rowIdFor(ticket.ticketId)!!).executeAsOne()

                    stored.status shouldBe "APPROVED"
                    stored.decided_by shouldBe "admin-1"
                    // The hash is over the CANONICAL code, domain-tagged. The returned code is
                    // the DISPLAY form, so normalize() is what bridges them.
                    stored.code_hash shouldBe
                        PepperedHasher(pepper).hash(
                            PasswordResetService.CODE_DOMAIN + ResetCodeGenerator.normalize(code),
                        )
                    // The plaintext must never be persisted.
                    stored.code_hash shouldNotBe code
                    stored.code_hash shouldNotBe ResetCodeGenerator.normalize(code)
                }
            }
        }

        test("the approved code is returned in readable grouped form") {
            withSqlDatabase {
                runTest {
                    sql.seedTestUser("ada")
                    val svc = service(sql)
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success).data

                    val code =
                        (
                            (svc.decide(ticket.ticketId, true, "admin-1") as AppResult.Success).data
                                as PasswordResetDecisionOutcome.Approved
                        ).code

                    // An admin reads this aloud — it must be the grouped form, not raw.
                    code shouldMatch Regex("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}")
                }
            }
        }

        test("denial records the decision and mints no code") {
            withSqlDatabase {
                runTest {
                    sql.seedTestUser("ada")
                    val svc = service(sql)
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success).data

                    val outcome = svc.decide(ticket.ticketId, approved = false, adminId = "admin-1")

                    (outcome as AppResult.Success).data shouldBe PasswordResetDecisionOutcome.Denied
                    val stored = sql.passwordResetRequestsQueries.selectById(svc.rowIdFor(ticket.ticketId)!!).executeAsOne()
                    stored.status shouldBe "DENIED"
                    stored.code_hash shouldBe null
                }
            }
        }

        test("deciding an unknown ticket fails as ResetRequestNotFound") {
            withSqlDatabase {
                runTest {
                    val outcome = service(sql).decide("no-such-ticket", approved = true, adminId = "admin-1")

                    (outcome as AppResult.Failure)
                        .error
                        .shouldBeInstanceOf<AuthError.ResetRequestNotFound>()
                }
            }
        }

        test("an already-decided ticket cannot be decided again") {
            withSqlDatabase {
                runTest {
                    sql.seedTestUser("ada")
                    val svc = service(sql)
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success).data
                    svc.decide(ticket.ticketId, approved = true, adminId = "admin-1")

                    val second = svc.decide(ticket.ticketId, approved = true, adminId = "admin-2")

                    (second as AppResult.Failure)
                        .error
                        .shouldBeInstanceOf<AuthError.ResetRequestNotFound>()
                }
            }
        }

        test("of two concurrent decisions on the same ticket, exactly one succeeds") {
            // Deliberately NOT kotlinx.coroutines.test.runTest: its TestDispatcher executes
            // coroutines on a single virtual-time thread, which would serialize these two
            // decide() calls rather than let them race — a race test that can't race is worse
            // than no test. runBlocking + async(Dispatchers.Default) puts each decide() on a
            // real OS thread so the two suspendTransaction bodies genuinely interleave, which is
            // what exercises the SQLITE_BUSY_SNAPSHOT retry this test exists to pin.
            withSqlDatabase {
                sql.seedTestUser("ada")
                val svc = service(sql)
                val ticket =
                    runBlocking {
                        (svc.request("ada@example.com", "claim-1") as AppResult.Success).data
                    }

                val (first, second) =
                    runBlocking {
                        coroutineScope {
                            val a = async(Dispatchers.Default) { svc.decide(ticket.ticketId, true, "admin-1") }
                            val b = async(Dispatchers.Default) { svc.decide(ticket.ticketId, true, "admin-2") }
                            a.await() to b.await()
                        }
                    }

                val outcomes = listOf(first, second)
                outcomes.count { it is AppResult.Success } shouldBe 1
                outcomes.count { it is AppResult.Failure } shouldBe 1
            }
        }

        test("two approvals of the same account yield different codes") {
            withSqlDatabase {
                runTest {
                    sql.seedTestUser("ada")
                    val svc = service(sql)
                    val first = (svc.request("ada@example.com", "c1") as AppResult.Success).data
                    val codeA =
                        (
                            (svc.decide(first.ticketId, true, "admin-1") as AppResult.Success).data
                                as PasswordResetDecisionOutcome.Approved
                        ).code
                    val second = (svc.request("ada@example.com", "c2") as AppResult.Success).data
                    val codeB =
                        (
                            (svc.decide(second.ticketId, true, "admin-1") as AppResult.Success).data
                                as PasswordResetDecisionOutcome.Approved
                        ).code

                    codeA shouldNotBe codeB
                }
            }
        }

        test("listPending shows who is asking") {
            withSqlDatabase {
                runTest {
                    sql.seedTestUser("ada")
                    val svc = service(sql)
                    svc.request("ada@example.com", "claim-1")

                    val pending = svc.listPending()

                    pending.size shouldBe 1
                    pending.single().email shouldBe "ada@example.com"
                    pending.single().displayName.isNotBlank() shouldBe true
                    // No code assertion here: PasswordResetRequest has no code field at all, so
                    // "never lists the code" is a compile-time guarantee, not a runtime one.
                }
            }
        }

        test("an approved request leaves the pending queue") {
            withSqlDatabase {
                runTest {
                    sql.seedTestUser("ada")
                    val svc = service(sql)
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success).data
                    svc.decide(ticket.ticketId, approved = true, adminId = "admin-1")

                    svc.listPending().size shouldBe 0
                }
            }
        }

        // ── complete() ──────────────────────────────────────────────────────────────

        test("the right code with the wrong claim fails as ResetCodeIncorrect") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                val svc = service(sql)
                runTest {
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success).data
                    val code = approvedCode(svc, ticket)

                    val result =
                        svc.complete(
                            ticketId = ticket.ticketId,
                            claimSecret = "wrong-claim",
                            code = code,
                            newPassword = "a-strong-new-password",
                        )

                    (result as AppResult.Failure).error.shouldBeInstanceOf<AuthError.ResetCodeIncorrect>()
                }
            }
        }

        test("the right claim with the wrong code fails as ResetCodeIncorrect") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                val svc = service(sql)
                runTest {
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success).data
                    approvedCode(svc, ticket)

                    val result =
                        svc.complete(
                            ticketId = ticket.ticketId,
                            claimSecret = "claim-1",
                            code = "ZZZZ-9999",
                            newPassword = "a-strong-new-password",
                        )

                    (result as AppResult.Failure).error.shouldBeInstanceOf<AuthError.ResetCodeIncorrect>()
                }
            }
        }

        test("both correct completes the reset, rewrites the password hash, and consumes the request") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                val svc = service(sql)
                runTest {
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success).data
                    val code = approvedCode(svc, ticket)

                    val result =
                        svc.complete(
                            ticketId = ticket.ticketId,
                            claimSecret = "claim-1",
                            code = code,
                            newPassword = "a-strong-new-password",
                        )

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    val user = sql.usersQueries.selectById("ada").executeAsOne()
                    user.password_hash shouldNotBe "phc"
                    PasswordHasher().verify("a-strong-new-password", user.password_hash) shouldBe true
                    sql.passwordResetRequestsQueries
                        .selectById(svc.rowIdFor(ticket.ticketId)!!)
                        .executeAsOne()
                        .status shouldBe "CONSUMED"
                }
            }
        }

        test("completion before approval fails as ResetNotApproved") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                val svc = service(sql)
                runTest {
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success).data

                    val result =
                        svc.complete(
                            ticketId = ticket.ticketId,
                            claimSecret = "claim-1",
                            code = "ABCD-2345",
                            newPassword = "a-strong-new-password",
                        )

                    (result as AppResult.Failure).error.shouldBeInstanceOf<AuthError.ResetNotApproved>()
                }
            }
        }

        test("a denied request fails as ResetNotApproved, not ResetRequestNotFound") {
            // Locks in the existing AuthError.ResetNotApproved contract: "still pending, or after
            // it was denied. Both collapse to one shape" — a denied ticket must not be
            // indistinguishable from an unknown one.
            withSqlDatabase {
                sql.seedTestUser("ada")
                val svc = service(sql)
                runTest {
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success).data
                    svc.decide(ticket.ticketId, approved = false, adminId = "admin-1")

                    val result =
                        svc.complete(
                            ticketId = ticket.ticketId,
                            claimSecret = "claim-1",
                            code = "ABCD-2345",
                            newPassword = "a-strong-new-password",
                        )

                    (result as AppResult.Failure).error.shouldBeInstanceOf<AuthError.ResetNotApproved>()
                }
            }
        }

        test("five wrong attempts exhaust the budget; a correct sixth try still fails") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                val svc = service(sql)
                runTest {
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success).data
                    val code = approvedCode(svc, ticket)

                    repeat(5) {
                        val wrong =
                            svc.complete(
                                ticketId = ticket.ticketId,
                                claimSecret = "claim-1",
                                code = "ZZZZ-9999",
                                newPassword = "a-strong-new-password",
                            )
                        (wrong as AppResult.Failure).error.shouldBeInstanceOf<AuthError.ResetCodeIncorrect>()
                    }

                    val sixth =
                        svc.complete(
                            ticketId = ticket.ticketId,
                            claimSecret = "claim-1",
                            code = code,
                            newPassword = "a-strong-new-password",
                        )

                    (sixth as AppResult.Failure).error.shouldBeInstanceOf<AuthError.ResetAttemptsExhausted>()
                }
            }
        }

        test("a consumed request cannot be replayed") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                val svc = service(sql)
                runTest {
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success).data
                    val code = approvedCode(svc, ticket)
                    svc.complete(
                        ticketId = ticket.ticketId,
                        claimSecret = "claim-1",
                        code = code,
                        newPassword = "a-strong-new-password",
                    )

                    val replay =
                        svc.complete(
                            ticketId = ticket.ticketId,
                            claimSecret = "claim-1",
                            code = code,
                            newPassword = "another-new-password",
                        )

                    (replay as AppResult.Failure).error.shouldBeInstanceOf<AuthError.ResetRequestNotFound>()
                }
            }
        }

        test("an expired request fails on read, with no purge having run") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                val requestingSvc = service(sql)
                lateinit var ticket: PasswordResetTicket
                lateinit var code: String
                runTest {
                    ticket = (requestingSvc.request("ada@example.com", "claim-1") as AppResult.Success).data
                    code = approvedCode(requestingSvc, ticket)
                }

                // A service built with a clock past the TTL — no purge has run, the row is still
                // physically present, but expiry must be enforced on read, not by a sweep.
                val laterSvc = service(sql, at = now + PasswordResetService.TTL + 1.minutes)
                runTest {
                    val result =
                        laterSvc.complete(
                            ticketId = ticket.ticketId,
                            claimSecret = "claim-1",
                            code = code,
                            newPassword = "a-strong-new-password",
                        )

                    (result as AppResult.Failure).error.shouldBeInstanceOf<AuthError.ResetRequestNotFound>()
                }
            }
        }

        test("a weak new password fails without burning an attempt") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                val svc = service(sql)
                runTest {
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success).data
                    approvedCode(svc, ticket)

                    val result =
                        svc.complete(
                            ticketId = ticket.ticketId,
                            claimSecret = "claim-1",
                            code = "ZZZZ-9999",
                            newPassword = "short",
                        )

                    (result as AppResult.Failure).error.shouldBeInstanceOf<AuthError.WeakPassword>()
                    sql.passwordResetRequestsQueries
                        .selectById(svc.rowIdFor(ticket.ticketId)!!)
                        .executeAsOne()
                        .attempts shouldBe 0L
                }
            }
        }

        test("a successful completion records exactly one session revocation for the account") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                val revoker = RecordingRevoker()
                val svc = service(sql, sessions = revoker)
                runTest {
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success).data
                    val code = approvedCode(svc, ticket)

                    val result =
                        svc.complete(
                            ticketId = ticket.ticketId,
                            claimSecret = "claim-1",
                            code = code,
                            newPassword = "a-strong-new-password",
                        )

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    revoker.revoked shouldBe listOf(UserId("ada"))
                }
            }
        }

        test("a failed completion records no session revocation") {
            withSqlDatabase {
                sql.seedTestUser("ada")
                val revoker = RecordingRevoker()
                val svc = service(sql, sessions = revoker)
                runTest {
                    val ticket = (svc.request("ada@example.com", "claim-1") as AppResult.Success).data
                    approvedCode(svc, ticket)

                    val result =
                        svc.complete(
                            ticketId = ticket.ticketId,
                            claimSecret = "claim-1",
                            code = "ZZZZ-9999",
                            newPassword = "a-strong-new-password",
                        )

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    revoker.revoked shouldBe emptyList()
                }
            }
        }

        test("concurrent wrong guesses against a 5-attempt budget never exceed it and never succeed") {
            // Deliberately NOT runTest — see "of two concurrent decisions" above: TestDispatcher's
            // virtual time would serialize these calls rather than let them race, and a race test
            // that can't race is worse than no test. runBlocking + async(Dispatchers.Default) puts
            // each complete() on a real OS thread so the ten calls genuinely interleave inside
            // suspendTransaction — which is exactly what a non-atomic attempt counter would fail.
            //
            // Uses a FAKE Argon2Limiter (the same counting-stand-in technique Argon2LimiterTest
            // uses), not the real production default. Empirically verified: with the REAL Argon2
            // hasher, each ~100-300ms hash (gated to 4 concurrent by Argon2Limiter, run BEFORE the
            // DB work — the same "hash outside the transaction" shape ProfileServiceImpl uses)
            // staggers the ten calls' completion times widely enough that they rarely reach the DB
            // read at the same instant on this machine, even with an artificial 50ms widening of
            // the read-to-write window — so the race this test exists to exercise essentially never
            // materializes. Swapping in a near-instant fake hash removes that confound and lets the
            // ten calls hit `selectById` at effectively the same moment, which is what actually
            // exercises suspendTransaction's SQLITE_BUSY_SNAPSHOT retry. Confirmed against a
            // deliberately-reintroduced TOCTOU version (read outside the transaction, like the
            // decide() bug this feature is modeled on): with the real hasher the buggy version
            // never failed this assertion across ten repeated runs; with the fake hasher it failed
            // immediately (attempts=10, budget bypassed) — proof this test's shape genuinely
            // detects the bug it is designed to catch, given a fast-enough hash.
            withSqlDatabase {
                sql.seedTestUser("ada")
                val fastPasswords = Argon2Limiter(permits = 10, hashFn = { "fake-hash" }, verifyFn = { _, _ -> true })
                val svc = service(sql, passwords = fastPasswords)
                val ticket =
                    runBlocking {
                        val t = (svc.request("ada@example.com", "claim-1") as AppResult.Success).data
                        approvedCode(svc, t)
                        t
                    }

                val results =
                    runBlocking {
                        coroutineScope {
                            (1..10)
                                .map {
                                    async(Dispatchers.Default) {
                                        svc.complete(
                                            ticketId = ticket.ticketId,
                                            claimSecret = "claim-1",
                                            code = "ZZZZ-9999",
                                            newPassword = "a-strong-new-password",
                                        )
                                    }
                                }.map { it.await() }
                        }
                    }

                results.none { it is AppResult.Success } shouldBe true
                sql.passwordResetRequestsQueries
                    .selectById(svc.rowIdFor(ticket.ticketId)!!)
                    .executeAsOne()
                    .attempts shouldBeLessThanOrEqual PasswordResetService.MAX_ATTEMPTS.toLong()
            }
        }
    })

// Records every revokeAll call — lets a test assert revocation happened without a real
// SessionService stack.
private class RecordingRevoker : SessionRevoker {
    val revoked = mutableListOf<UserId>()

    override suspend fun revokeAll(userId: UserId) {
        revoked += userId
    }
}
