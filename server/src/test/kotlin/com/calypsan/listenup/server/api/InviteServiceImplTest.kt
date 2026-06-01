@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.invite.InviteStatus
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InviteError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.InviteCodeGenerator
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.InviteEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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

        fun makeInviteService(db: Database): InviteServiceImpl =
            InviteServiceImpl(
                db = db,
                codeGenerator = InviteCodeGenerator(),
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
    })

private fun <T> AppResult<T>.shouldSucceed(): T = shouldBeInstanceOf<AppResult.Success<T>>().data

private inline fun <reified E : AppError> AppResult<*>.shouldFail(): E =
    shouldBeInstanceOf<AppResult.Failure>()
        .error
        .shouldBeInstanceOf<E>()
