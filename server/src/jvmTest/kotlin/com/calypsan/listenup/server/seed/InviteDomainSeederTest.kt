@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.seed

import com.calypsan.listenup.server.auth.InviteCodeGenerator
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.SessionIssuer
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.api.InviteServiceImpl
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class InviteDomainSeederTest :
    FunSpec({
        test("seeds exactly one unclaimed demo invite; isAlreadySeeded flips false→true") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                val seeder = InviteDomainSeeder(sql, makeInviteService(sql))

                runTest {
                    seeder.isAlreadySeeded() shouldBe false
                    seeder.seed()
                    seeder.isAlreadySeeded() shouldBe true

                    val invites =
                        sql.invitesQueries
                            .selectAll()
                            .executeAsList()
                            .map { it.email to it.claimed_at }
                    invites.size shouldBe 1
                    invites.single().first shouldBe InviteDomainSeeder.INVITE_EMAIL
                    invites.single().second.shouldBeNull()
                }
            }
        }

        test("seed() runs after a ROOT exists and stamps created_by from that root") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                val seeder = InviteDomainSeeder(sql, makeInviteService(sql))

                runTest {
                    seeder.seed()
                    val createdBy =
                        sql.invitesQueries
                            .selectAll()
                            .executeAsList()
                            .single()
                            .created_by
                    createdBy shouldBe "root1"
                }
            }
        }
    })

private fun makeInviteService(sql: ListenUpDatabase): InviteServiceImpl {
    val clock = FixedClock(Instant.parse("2026-05-02T12:00:00Z"))
    val pepper = "x".repeat(32).toByteArray()
    val sessions = SessionService(sql, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
    val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
    return InviteServiceImpl(
        db = sql,
        codeGenerator = InviteCodeGenerator(),
        hasher = Argon2Limiter(PasswordHasher()),
        sessionIssuer = SessionIssuer(sessions, jwt, clock),
        serverName = "Test Library",
        clock = clock,
    )
}
