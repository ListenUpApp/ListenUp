@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.invite.InviteDto
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.InviteCodeGenerator
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.SessionIssuer
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.AdminUserRosterMaintainer
import com.calypsan.listenup.server.sync.AdminUserRosterRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Verifies [InviteServiceImpl.claimInvite] publishes a fresh `admin_user_roster` row for the
 * newly-created user via [AdminUserRosterMaintainer] — mirrors the `PublicProfileMaintainer`
 * wiring in `AuthServiceImpl.register`, but for the invite-claim admission path.
 */
class InviteServiceRosterPublishTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun principalFor(
            userId: String,
            role: UserRole,
        ): PrincipalProvider = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("session-$userId"), role) }

        fun sessionIssuerFor(sql: ListenUpDatabase): SessionIssuer {
            val pepper = "x".repeat(32).toByteArray()
            val sessions =
                SessionService(sql, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = fixedClock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, fixedClock)
            return SessionIssuer(sessions, jwt, fixedClock)
        }

        test("claimInvite publishes an admin_user_roster row for the newly-claimed user") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)

                val rosterRepo =
                    AdminUserRosterRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                val rosterMaintainer = AdminUserRosterMaintainer(sql, rosterRepo)

                fun makeInviteService(): InviteServiceImpl =
                    InviteServiceImpl(
                        db = sql,
                        codeGenerator = InviteCodeGenerator(),
                        hasher = Argon2Limiter(PasswordHasher()),
                        sessionIssuer = sessionIssuerFor(sql),
                        serverName = "Test Library",
                        clock = fixedClock,
                        adminUserRosterMaintainer = rosterMaintainer,
                    )

                runTest {
                    val admin = makeInviteService().copyWith(principalFor("root1", UserRole.ROOT))
                    val invite: InviteDto =
                        admin
                            .createInvite("a@b.c", "A", UserRole.MEMBER, null)
                            .shouldBeInstanceOf<AppResult.Success<InviteDto>>()
                            .data

                    val session: AuthSession =
                        makeInviteService()
                            .claimInvite(invite.code, "password123")
                            .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                            .data
                    session.accessToken.value.shouldNotBeBlank()

                    val newUserId =
                        sql.usersQueries
                            .selectByEmailNormalized("a@b.c")
                            .executeAsOneOrNull()!!
                            .id

                    val page = rosterRepo.pullSince(userId = null, cursor = 0, limit = 100)
                    val rosterRow = page.items.singleOrNull { it.id == newUserId }
                    rosterRow.shouldNotBeNull()
                    rosterRow.email shouldBe "a@b.c"
                    rosterRow.displayName shouldBe "A"
                    rosterRow.role shouldBe "MEMBER"
                    rosterRow.status shouldBe "ACTIVE"
                }
            }
        }
    })
