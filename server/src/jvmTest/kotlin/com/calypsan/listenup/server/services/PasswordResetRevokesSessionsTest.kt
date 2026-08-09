@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.auth.PasswordResetDecisionOutcome
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PepperedHasher
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.ResetCodeGenerator
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.migratedTestDatabase
import com.calypsan.listenup.server.testing.seedTestUser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Isolated from [PasswordResetServiceTest] so that spec's fixture stays a single-database
 * ([withSqlDatabase]) shape — this test needs a real [SessionService] wired over the same
 * database, mirroring the fixture [SessionServiceTest] uses.
 */
class PasswordResetRevokesSessionsTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

        test("completing a reset revokes every session for the account") {
            val db = migratedTestDatabase().db
            db.seedTestUser("ada")

            val sessions =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = FixedClock(now))
            val resets =
                PasswordResetService(
                    db = db,
                    hasher = PepperedHasher(pepper),
                    codes = ResetCodeGenerator(),
                    clock = FixedClock(now),
                    // Real wiring shape: complete() only ever needs revokeAll, so it depends on
                    // the narrowed SessionRevoker, not the full SessionService.
                    sessions = SessionRevoker { sessions.revokeAll(it) },
                )

            runTest {
                sessions.createSession(UserId("ada"), label = "phone")
                sessions.createSession(UserId("ada"), label = "laptop")
                sessions.listActiveFor(UserId("ada")).size shouldBe 2

                val ticket = (resets.request("ada@example.com", "claim-1") as AppResult.Success).data
                val code =
                    (
                        (resets.decide(ticket.ticketId, true, "admin-1") as AppResult.Success).data
                            as PasswordResetDecisionOutcome.Approved
                    ).code

                val result =
                    resets.complete(
                        ticketId = ticket.ticketId,
                        claimSecret = "claim-1",
                        code = code,
                        newPassword = "a-strong-new-password",
                    )

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                sessions.listActiveFor(UserId("ada")).size shouldBe 0
            }
        }
    })
