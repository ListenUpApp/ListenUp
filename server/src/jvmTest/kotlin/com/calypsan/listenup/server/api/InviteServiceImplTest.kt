@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.invite.InviteStatus
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InviteError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.InviteCodeGenerator
import com.calypsan.listenup.server.auth.InviteRateLimiter
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
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [InviteServiceImpl].
 *
 * Real in-memory Flyway-migrated SQLite + real [InviteCodeGenerator]; no mocks.
 * The acting caller is supplied via a [PrincipalProvider] stub; [actAs] rebinds
 * the service to a chosen `(userId, role)` so a single test can exercise
 * multiple callers — mirrors [AdminUserServiceImplTest].
 */
class InviteServiceImplTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(UserId(userId), SessionId("session-$userId"), role)
            }

        val serverName = "Test Library"

        fun sessionIssuerFor(sql: ListenUpDatabase): SessionIssuer {
            val pepper = "x".repeat(32).toByteArray()
            val sessions =
                SessionService(sql, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = fixedClock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, fixedClock)
            return SessionIssuer(sessions, jwt, fixedClock)
        }

        fun makeInviteService(
            sql: ListenUpDatabase,
            hasher: Argon2Limiter = Argon2Limiter(PasswordHasher()),
            inviteRateLimiter: InviteRateLimiter? = null,
        ): InviteServiceImpl =
            InviteServiceImpl(
                db = sql,
                codeGenerator = InviteCodeGenerator(),
                hasher = hasher,
                sessionIssuer = sessionIssuerFor(sql),
                serverName = serverName,
                clock = fixedClock,
                inviteRateLimiter = inviteRateLimiter,
            )

        fun InviteServiceImpl.actAs(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): InviteServiceImpl = copyWith(principalFor(userId, role))

        test("createInvite by an admin generates a code, stamps created_by, defaults 7-day expiry") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val svc = makeInviteService(sql).actAs("root1", UserRole.ROOT)
                    val invite =
                        svc.createInvite("a@b.c", "A", UserRole.MEMBER, null).shouldSucceed()
                    invite.code.shouldNotBeBlank()
                    invite.email shouldBe "a@b.c"
                    invite.displayName shouldBe "A"
                    invite.role shouldBe UserRole.MEMBER
                    invite.createdBy shouldBe "root1"
                    val expectedExpiry =
                        fixedClock.now().toEpochMilliseconds() + 7L * 24 * 60 * 60 * 1000
                    invite.expiresAt shouldBe expectedExpiry
                    invite.createdAt shouldBe fixedClock.now().toEpochMilliseconds()
                }
            }
        }

        test("createInvite by a member is denied") {
            withSqlDatabase {
                sql.seedTestUser("m1", UserRoleColumn.MEMBER)
                runTest {
                    val svc = makeInviteService(sql).actAs("m1", UserRole.MEMBER)
                    svc
                        .createInvite("a@b.c", "A", UserRole.MEMBER, null)
                        .shouldFail<AuthError.PermissionDenied>()
                }
            }
        }

        test("createInvite rejects a malformed email") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val svc = makeInviteService(sql).actAs("root1", UserRole.ROOT)
                    svc
                        .createInvite("notanemail", "A", UserRole.MEMBER, null)
                        .shouldFail<InviteError.InvalidInput>()
                }
            }
        }

        test("createInvite rejects a blank displayName") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val svc = makeInviteService(sql).actAs("root1", UserRole.ROOT)
                    svc
                        .createInvite("a@b.c", "   ", UserRole.MEMBER, null)
                        .shouldFail<InviteError.InvalidInput>()
                }
            }
        }

        test("createInvite rejects a non-positive expiresInDays") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val svc = makeInviteService(sql).actAs("root1", UserRole.ROOT)
                    svc.createInvite("a@b.c", "A", UserRole.MEMBER, 0).shouldFail<InviteError.InvalidInput>()
                    svc.createInvite("a@b.c", "A", UserRole.MEMBER, -1).shouldFail<InviteError.InvalidInput>()
                }
            }
        }

        test("createInvite with role=ROOT by an ADMIN caller is denied") {
            withSqlDatabase {
                sql.seedTestUser("a1", UserRoleColumn.ADMIN)
                runTest {
                    val svc = makeInviteService(sql).actAs("a1", UserRole.ADMIN)
                    svc
                        .createInvite("a@b.c", "A", UserRole.ROOT, null)
                        .shouldFail<AuthError.PermissionDenied>()
                }
            }
        }

        test("createInvite with role=ROOT by a ROOT caller succeeds") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val svc = makeInviteService(sql).actAs("root1", UserRole.ROOT)
                    svc.createInvite("a@b.c", "A", UserRole.ROOT, null).shouldSucceed().role shouldBe UserRole.ROOT
                }
            }
        }

        test("createInvite by an ADMIN caller can still mint MEMBER and ADMIN invites") {
            withSqlDatabase {
                sql.seedTestUser("a1", UserRoleColumn.ADMIN)
                runTest {
                    val svc = makeInviteService(sql).actAs("a1", UserRole.ADMIN)
                    svc.createInvite("m@b.c", "M", UserRole.MEMBER, null).shouldSucceed().role shouldBe UserRole.MEMBER
                    svc.createInvite("a2@b.c", "A2", UserRole.ADMIN, null).shouldSucceed().role shouldBe UserRole.ADMIN
                }
            }
        }

        test("listInvites returns created invites with derived status PENDING") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val svc = makeInviteService(sql).actAs("root1", UserRole.ROOT)
                    svc.createInvite("a@b.c", "A", UserRole.MEMBER, null).shouldSucceed()
                    val listed = svc.listInvites().shouldSucceed()
                    listed.size shouldBe 1
                    listed.single().status shouldBe InviteStatus.PENDING
                }
            }
        }

        test("revokeInvite deletes an unclaimed invite") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val svc = makeInviteService(sql).actAs("root1", UserRole.ROOT)
                    val invite = svc.createInvite("a@b.c", "A", UserRole.MEMBER, null).shouldSucceed()
                    svc.revokeInvite(invite.id).shouldSucceed()
                    svc.listInvites().shouldSucceed().size shouldBe 0
                }
            }
        }

        test("revokeInvite rejects a claimed invite") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val svc = makeInviteService(sql).actAs("root1", UserRole.ROOT)
                    val invite = svc.createInvite("a@b.c", "A", UserRole.MEMBER, null).shouldSucceed()
                    sql.invitesQueries.markClaimed(
                        claimed_at = fixedClock.now().toEpochMilliseconds(),
                        claimed_by = "someuser",
                        id = invite.id.value,
                    )
                    svc.revokeInvite(invite.id).shouldFail<InviteError.AlreadyClaimed>()
                }
            }
        }

        // ── claimInvite / lookupInvite (public, no principal) ───────────────────

        test("claimInvite creates an ACTIVE user, sets invited_by, marks claimed, issues a session") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val admin = makeInviteService(sql).actAs("root1", UserRole.ROOT)
                    val invite = admin.createInvite("a@b.c", "A", UserRole.MEMBER, null).shouldSucceed()

                    val claimer = makeInviteService(sql)
                    val session = claimer.claimInvite(invite.code, "password123").shouldSucceed()
                    session.shouldBeInstanceOf<AuthSession>()
                    session.accessToken.value.shouldNotBeBlank()

                    val newUser =
                        sql.usersQueries.selectByEmailNormalized("a@b.c").executeAsOneOrNull()!!.let {
                            Triple(it.id, it.status to it.role, it.invited_by)
                        }
                    val (newUserId, statusRole, invitedBy) = newUser
                    statusRole shouldBe ("ACTIVE" to "MEMBER")
                    invitedBy shouldBe "root1"

                    val claimed =
                        sql.invitesQueries.selectById(invite.id.value).executeAsOneOrNull()!!.let {
                            it.claimed_at to it.claimed_by
                        }
                    claimed.first.shouldNotBeNull()
                    claimed.second shouldBe newUserId
                }
            }
        }

        test("claim persists the DeviceInfo onto the issued session") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val admin = makeInviteService(sql).actAs("root1", UserRole.ROOT)
                    val invite = admin.createInvite("new@x.co", "New", UserRole.MEMBER, null).shouldSucceed()

                    val session =
                        makeInviteService(sql)
                            .claimInvite(
                                invite.code,
                                "password123",
                                deviceInfo = DeviceInfo(deviceModel = "iPad", platform = "iPadOS"),
                            ).shouldSucceed()

                    val persisted =
                        sql.sessionsQueries.selectById(session.sessionId.value).executeAsOneOrNull()!!.let {
                            it.device_model to it.platform
                        }
                    persisted.first shouldBe "iPad"
                    persisted.second shouldBe "iPadOS"
                }
            }
        }

        test("claimInvite succeeds even when RegistrationPolicy is CLOSED") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                        .setRegistrationPolicy(RegistrationPolicy.CLOSED)
                    val admin = makeInviteService(sql).actAs("root1", UserRole.ROOT)
                    val invite = admin.createInvite("a@b.c", "A", UserRole.MEMBER, null).shouldSucceed()

                    makeInviteService(sql).claimInvite(invite.code, "password123").shouldSucceed()
                }
            }
        }

        test("claimInvite on an unknown code returns NotFound") {
            withSqlDatabase {
                runTest {
                    makeInviteService(sql).claimInvite("no-such-code", "password123").shouldFail<InviteError.NotFound>()
                }
            }
        }

        test("claimInvite on an expired invite returns Expired") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val admin = makeInviteService(sql).actAs("root1", UserRole.ROOT)
                    val invite = admin.createInvite("a@b.c", "A", UserRole.MEMBER, null).shouldSucceed()
                    sql.invitesQueries.updateExpiresAt(
                        expires_at = fixedClock.now().toEpochMilliseconds() - 1,
                        id = invite.id.value,
                    )
                    makeInviteService(sql).claimInvite(invite.code, "password123").shouldFail<InviteError.Expired>()
                }
            }
        }

        test("claimInvite on an already-claimed invite returns AlreadyClaimed") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val admin = makeInviteService(sql).actAs("root1", UserRole.ROOT)
                    val invite = admin.createInvite("a@b.c", "A", UserRole.MEMBER, null).shouldSucceed()
                    makeInviteService(sql).claimInvite(invite.code, "password123").shouldSucceed()
                    // Second claim of the same one-use code must be refused.
                    makeInviteService(sql)
                        .claimInvite(invite.code, "password123")
                        .shouldFail<InviteError.AlreadyClaimed>()
                }
            }
        }

        test("claimInvite when the email already has an account returns EmailInUse") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val admin = makeInviteService(sql).actAs("root1", UserRole.ROOT)
                    val invite = admin.createInvite("taken@b.c", "Taken", UserRole.MEMBER, null).shouldSucceed()
                    sql.usersQueries.insert(
                        id = "existing",
                        email = "taken@b.c",
                        email_normalized = "taken@b.c",
                        password_hash = "phc",
                        role = UserRoleColumn.MEMBER.name,
                        display_name = "Taken",
                        status = UserStatusColumn.ACTIVE.name,
                        created_at = 1L,
                        updated_at = 1L,
                        last_login_at = null,
                        can_edit = 1L,
                        can_share = 1L,
                        approved_by = null,
                        approved_at = null,
                        deleted_at = null,
                        invited_by = null,
                        tagline = null,
                        avatar_type = "auto",
                        timezone = "UTC",
                    )
                    makeInviteService(sql).claimInvite(invite.code, "password123").shouldFail<InviteError.EmailInUse>()
                }
            }
        }

        test("lookupInvite returns a preview for a valid code; valid=false for expired/claimed") {
            withSqlDatabase {
                sql.seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val admin = makeInviteService(sql).actAs("root1", UserRole.ROOT)
                    val invite = admin.createInvite("a@b.c", "A", UserRole.MEMBER, null).shouldSucceed()

                    val preview = makeInviteService(sql).lookupInvite(invite.code).shouldSucceed()
                    preview.displayName shouldBe "A"
                    preview.email shouldBe "a@b.c"
                    preview.invitedByName shouldBe "root1"
                    preview.serverName shouldBe serverName
                    preview.valid shouldBe true
                    preview.invalidReason shouldBe null

                    sql.invitesQueries.updateExpiresAt(
                        expires_at = fixedClock.now().toEpochMilliseconds() - 1,
                        id = invite.id.value,
                    )
                    val expired = makeInviteService(sql).lookupInvite(invite.code).shouldSucceed()
                    expired.valid shouldBe false
                    expired.invalidReason shouldBe "This invite has expired."
                }
            }
        }

        // ── RPC public-mount rate limiting (SEC-02) ─────────────────────────────

        test("claimInvite rate-limits repeated attempts from one host; a different host is unaffected") {
            withSqlDatabase {
                runTest {
                    val limiter = InviteRateLimiter(clock = fixedClock)
                    val base = makeInviteService(sql, inviteRateLimiter = limiter)
                    // CLAIM bucket ceiling is 5/min — the invite code doesn't need to be valid for the
                    // throttle to count the attempt (mirrors AuthServiceImpl's login throttle, which
                    // counts before credential validation).
                    repeat(5) { i ->
                        base.withRemoteHost("1.1.1.1").claimInvite("bogus-$i", "password123").shouldFail<InviteError.NotFound>()
                    }
                    val throttled =
                        base
                            .withRemoteHost("1.1.1.1")
                            .claimInvite("bogus-6", "password123")
                            .shouldFail<AuthError.RateLimited>()
                    throttled.retryAfterSeconds shouldBeGreaterThan 0
                    // A different host has its own bucket and is unaffected.
                    base
                        .withRemoteHost("2.2.2.2")
                        .claimInvite("bogus-x", "password123")
                        .shouldFail<InviteError.NotFound>()
                }
            }
        }

        test("lookupInvite rate-limits repeated attempts from one host; a different host is unaffected") {
            withSqlDatabase {
                runTest {
                    val limiter = InviteRateLimiter(clock = fixedClock)
                    val base = makeInviteService(sql, inviteRateLimiter = limiter)
                    // LOOKUP bucket ceiling is 20/min.
                    repeat(20) { i ->
                        base.withRemoteHost("1.1.1.1").lookupInvite("bogus-$i").shouldFail<InviteError.NotFound>()
                    }
                    val throttled =
                        base.withRemoteHost("1.1.1.1").lookupInvite("bogus-21").shouldFail<AuthError.RateLimited>()
                    throttled.retryAfterSeconds shouldBeGreaterThan 0
                    base.withRemoteHost("2.2.2.2").lookupInvite("bogus-x").shouldFail<InviteError.NotFound>()
                }
            }
        }

        test("claimInvite on an unknown code fails WITHOUT hashing the password") {
            withSqlDatabase {
                runTest {
                    var hashCalls = 0
                    val countingHasher =
                        Argon2Limiter(
                            permits = 1,
                            hashFn = {
                                hashCalls++
                                "hash"
                            },
                            verifyFn = { _, _ -> false },
                        )
                    val svc = makeInviteService(sql, hasher = countingHasher)
                    svc.claimInvite("no-such-code", "password123").shouldFail<InviteError.NotFound>()
                    hashCalls shouldBe 0
                }
            }
        }
    })

private fun <T> AppResult<T>.shouldSucceed(): T = shouldBeInstanceOf<AppResult.Success<T>>().data

private inline fun <reified E : AppError> AppResult<*>.shouldFail(): E =
    shouldBeInstanceOf<AppResult.Failure>()
        .error
        .shouldBeInstanceOf<E>()
