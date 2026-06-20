@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.seed

import com.calypsan.listenup.server.auth.InviteCodeGenerator
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.SessionIssuer
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.api.InviteServiceImpl
import com.calypsan.listenup.server.db.InviteEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class InviteDomainSeederTest :
    FunSpec({
        test("seeds exactly one unclaimed demo invite; isAlreadySeeded flips false→true") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                val seeder = InviteDomainSeeder(db.asSqlDatabase(), makeInviteService(db))

                runTest {
                    seeder.isAlreadySeeded() shouldBe false
                    seeder.seed()
                    seeder.isAlreadySeeded() shouldBe true

                    val invites = suspendTransaction(db) { InviteEntity.all().toList().map { it.email to it.claimedAt } }
                    invites.size shouldBe 1
                    invites.single().first shouldBe InviteDomainSeeder.INVITE_EMAIL
                    invites.single().second.shouldBeNull()
                }
            }
        }

        test("seed() runs after a ROOT exists and stamps created_by from that root") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                val seeder = InviteDomainSeeder(db.asSqlDatabase(), makeInviteService(db))

                runTest {
                    seeder.seed()
                    val createdBy = suspendTransaction(db) { InviteEntity.all().single().createdBy }
                    createdBy shouldBe "root1"
                }
            }
        }
    })

private fun makeInviteService(db: Database): InviteServiceImpl {
    val clock = FixedClock(Instant.parse("2026-05-02T12:00:00Z"))
    val pepper = "x".repeat(32).toByteArray()
    val sessions = SessionService(db.asSqlDatabase(), RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
    val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
    return InviteServiceImpl(
        db = db.asSqlDatabase(),
        codeGenerator = InviteCodeGenerator(),
        hasher = PasswordHasher(),
        sessionIssuer = SessionIssuer(sessions, jwt, clock),
        serverName = "Test Library",
        clock = clock,
    )
}
