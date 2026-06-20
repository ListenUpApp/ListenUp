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
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.SessionIssuer
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.InviteEntity
import com.calypsan.listenup.server.db.SessionEntity
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.db.UserTable
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

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

        fun sessionIssuerFor(db: Database): SessionIssuer {
            val pepper = "x".repeat(32).toByteArray()
            val sessions =
                SessionService(db.asSqlDatabase(), RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = fixedClock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, fixedClock)
            return SessionIssuer(sessions, jwt, fixedClock)
        }

        fun makeInviteService(db: Database): InviteServiceImpl =
            InviteServiceImpl(
                db = db.asSqlDatabase(),
                codeGenerator = InviteCodeGenerator(),
                hasher = PasswordHasher(),
                sessionIssuer = sessionIssuerFor(db),
                serverName = serverName,
                clock = fixedClock,
            )

        fun InviteServiceImpl.actAs(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): InviteServiceImpl = copyWith(principalFor(userId, role))

        test("createInvite by an admin generates a code, stamps created_by, defaults 7-day expiry") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val svc = makeInviteService(db).actAs("root1", UserRole.ROOT)
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
            withInMemoryDatabase {
                val db = this
                seedTestUser("m1", UserRoleColumn.MEMBER)
                runTest {
                    val svc = makeInviteService(db).actAs("m1", UserRole.MEMBER)
                    svc
                        .createInvite("a@b.c", "A", UserRole.MEMBER, null)
                        .shouldFail<AuthError.PermissionDenied>()
                }
            }
        }

        test("createInvite rejects a malformed email") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val svc = makeInviteService(db).actAs("root1", UserRole.ROOT)
                    svc
                        .createInvite("notanemail", "A", UserRole.MEMBER, null)
                        .shouldFail<InviteError.InvalidInput>()
                }
            }
        }

        test("createInvite rejects a blank displayName") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val svc = makeInviteService(db).actAs("root1", UserRole.ROOT)
                    svc
                        .createInvite("a@b.c", "   ", UserRole.MEMBER, null)
                        .shouldFail<InviteError.InvalidInput>()
                }
            }
        }

        test("createInvite rejects a non-positive expiresInDays") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val svc = makeInviteService(db).actAs("root1", UserRole.ROOT)
                    svc.createInvite("a@b.c", "A", UserRole.MEMBER, 0).shouldFail<InviteError.InvalidInput>()
                    svc.createInvite("a@b.c", "A", UserRole.MEMBER, -1).shouldFail<InviteError.InvalidInput>()
                }
            }
        }

        test("listInvites returns created invites with derived status PENDING") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val svc = makeInviteService(db).actAs("root1", UserRole.ROOT)
                    svc.createInvite("a@b.c", "A", UserRole.MEMBER, null).shouldSucceed()
                    val listed = svc.listInvites().shouldSucceed()
                    listed.size shouldBe 1
                    listed.single().status shouldBe InviteStatus.PENDING
                }
            }
        }

        test("revokeInvite deletes an unclaimed invite") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val svc = makeInviteService(db).actAs("root1", UserRole.ROOT)
                    val invite = svc.createInvite("a@b.c", "A", UserRole.MEMBER, null).shouldSucceed()
                    svc.revokeInvite(invite.id).shouldSucceed()
                    svc.listInvites().shouldSucceed().size shouldBe 0
                }
            }
        }

        test("revokeInvite rejects a claimed invite") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val svc = makeInviteService(db).actAs("root1", UserRole.ROOT)
                    val invite = svc.createInvite("a@b.c", "A", UserRole.MEMBER, null).shouldSucceed()
                    transaction(db) {
                        InviteEntity.findById(invite.id.value)!!.apply {
                            claimedAt = fixedClock.now().toEpochMilliseconds()
                            claimedBy = "someuser"
                        }
                    }
                    svc.revokeInvite(invite.id).shouldFail<InviteError.AlreadyClaimed>()
                }
            }
        }

        // ── claimInvite / lookupInvite (public, no principal) ───────────────────

        test("claimInvite creates an ACTIVE user, sets invited_by, marks claimed, issues a session") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val admin = makeInviteService(db).actAs("root1", UserRole.ROOT)
                    val invite = admin.createInvite("a@b.c", "A", UserRole.MEMBER, null).shouldSucceed()

                    val claimer = makeInviteService(db)
                    val session = claimer.claimInvite(invite.code, "password123").shouldSucceed()
                    session.shouldBeInstanceOf<AuthSession>()
                    session.accessToken.value.shouldNotBeBlank()

                    val newUser =
                        transaction(db) {
                            UserEntity.find { UserTable.emailNormalized eq "a@b.c" }.single().let {
                                Triple(it.id.value, it.status to it.role, it.invitedBy)
                            }
                        }
                    val (newUserId, statusRole, invitedBy) = newUser
                    statusRole shouldBe (UserStatusColumn.ACTIVE to UserRoleColumn.MEMBER)
                    invitedBy shouldBe "root1"

                    val claimed =
                        transaction(db) {
                            InviteEntity.findById(invite.id.value)!!.let { it.claimedAt to it.claimedBy }
                        }
                    claimed.first.shouldNotBeNull()
                    claimed.second shouldBe newUserId
                }
            }
        }

        test("claim persists the DeviceInfo onto the issued session") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val admin = makeInviteService(db).actAs("root1", UserRole.ROOT)
                    val invite = admin.createInvite("new@x.co", "New", UserRole.MEMBER, null).shouldSucceed()

                    val session =
                        makeInviteService(db)
                            .claimInvite(
                                invite.code,
                                "password123",
                                deviceInfo = DeviceInfo(deviceModel = "iPad", platform = "iPadOS"),
                            ).shouldSucceed()

                    val persisted =
                        transaction(db) {
                            SessionEntity.findById(session.sessionId.value)!!.let { it.deviceModel to it.platform }
                        }
                    persisted.first shouldBe "iPad"
                    persisted.second shouldBe "iPadOS"
                }
            }
        }

        test("claimInvite succeeds even when RegistrationPolicy is CLOSED") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    ServerSettingsRepository(db.asSqlDatabase(), default = RegistrationPolicy.OPEN)
                        .setRegistrationPolicy(RegistrationPolicy.CLOSED)
                    val admin = makeInviteService(db).actAs("root1", UserRole.ROOT)
                    val invite = admin.createInvite("a@b.c", "A", UserRole.MEMBER, null).shouldSucceed()

                    makeInviteService(db).claimInvite(invite.code, "password123").shouldSucceed()
                }
            }
        }

        test("claimInvite on an unknown code returns NotFound") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    makeInviteService(db).claimInvite("no-such-code", "password123").shouldFail<InviteError.NotFound>()
                }
            }
        }

        test("claimInvite on an expired invite returns Expired") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val admin = makeInviteService(db).actAs("root1", UserRole.ROOT)
                    val invite = admin.createInvite("a@b.c", "A", UserRole.MEMBER, null).shouldSucceed()
                    transaction(db) {
                        InviteEntity.findById(invite.id.value)!!.expiresAt =
                            fixedClock.now().toEpochMilliseconds() - 1
                    }
                    makeInviteService(db).claimInvite(invite.code, "password123").shouldFail<InviteError.Expired>()
                }
            }
        }

        test("claimInvite on an already-claimed invite returns AlreadyClaimed") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val admin = makeInviteService(db).actAs("root1", UserRole.ROOT)
                    val invite = admin.createInvite("a@b.c", "A", UserRole.MEMBER, null).shouldSucceed()
                    makeInviteService(db).claimInvite(invite.code, "password123").shouldSucceed()
                    // Second claim of the same one-use code must be refused.
                    makeInviteService(db)
                        .claimInvite(invite.code, "password123")
                        .shouldFail<InviteError.AlreadyClaimed>()
                }
            }
        }

        test("claimInvite when the email already has an account returns EmailInUse") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val admin = makeInviteService(db).actAs("root1", UserRole.ROOT)
                    val invite = admin.createInvite("taken@b.c", "Taken", UserRole.MEMBER, null).shouldSucceed()
                    transaction(db) {
                        UserEntity.new("existing") {
                            email = "taken@b.c"
                            emailNormalized = "taken@b.c"
                            passwordHash = "phc"
                            role = UserRoleColumn.MEMBER
                            displayName = "Taken"
                            status = UserStatusColumn.ACTIVE
                            createdAt = 1L
                            updatedAt = 1L
                        }
                    }
                    makeInviteService(db).claimInvite(invite.code, "password123").shouldFail<InviteError.EmailInUse>()
                }
            }
        }

        test("lookupInvite returns a preview for a valid code; valid=false for expired/claimed") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("root1", UserRoleColumn.ROOT)
                runTest {
                    val admin = makeInviteService(db).actAs("root1", UserRole.ROOT)
                    val invite = admin.createInvite("a@b.c", "A", UserRole.MEMBER, null).shouldSucceed()

                    val preview = makeInviteService(db).lookupInvite(invite.code).shouldSucceed()
                    preview.displayName shouldBe "A"
                    preview.email shouldBe "a@b.c"
                    preview.invitedByName shouldBe "root1"
                    preview.serverName shouldBe serverName
                    preview.valid shouldBe true
                    preview.invalidReason shouldBe null

                    transaction(db) {
                        InviteEntity.findById(invite.id.value)!!.expiresAt =
                            fixedClock.now().toEpochMilliseconds() - 1
                    }
                    val expired = makeInviteService(db).lookupInvite(invite.code).shouldSucceed()
                    expired.valid shouldBe false
                    expired.invalidReason shouldBe "This invite has expired."
                }
            }
        }
    })

private fun <T> AppResult<T>.shouldSucceed(): T = shouldBeInstanceOf<AppResult.Success<T>>().data

private inline fun <reified E : AppError> AppResult<*>.shouldFail(): E =
    shouldBeInstanceOf<AppResult.Failure>()
        .error
        .shouldBeInstanceOf<E>()
