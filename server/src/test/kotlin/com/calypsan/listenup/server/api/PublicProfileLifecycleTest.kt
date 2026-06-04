@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.RegistrationBroadcaster
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Instant

/**
 * Integration tests verifying that [PublicProfileMaintainer] is triggered across
 * the user-identity and user-lifecycle surfaces:
 *
 * 1. `updateMyProfile(displayName = "New Name")` → projection reflects the new name.
 * 2. Admin approves a pending registration → a projection row is created.
 * 3. Admin deletes a user → projection row is soft-deleted.
 */
class PublicProfileLifecycleTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()
        val fixedClock =
            com.calypsan.listenup.server.testing
                .FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        /**
         * Builds a [PublicProfileMaintainer] + [PublicProfileRepository] pair over the given
         * database. Returns both so the test can query the projection after each act.
         */
        fun Database.buildMaintainerAndRepo(): Pair<PublicProfileMaintainer, PublicProfileRepository> {
            val repo = PublicProfileRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
            val maintainer = PublicProfileMaintainer(db = this, publicProfileRepo = repo)
            return maintainer to repo
        }

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(UserId(userId), SessionId("session-$userId"), role)
            }

        fun Database.makeAdminUserService(
            maintainer: PublicProfileMaintainer,
        ): AdminUserServiceImpl {
            val sessions = SessionService(this, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = fixedClock)
            val settings = ServerSettingsRepository(this, default = RegistrationPolicy.OPEN)
            return AdminUserServiceImpl(
                db = this,
                sessions = sessions,
                settings = settings,
                clock = fixedClock,
                registrationBroadcaster = RegistrationBroadcaster(),
                bus = ChangeBus(),
                publicProfileMaintainer = maintainer,
            )
        }

        /** Seeds a PENDING_APPROVAL user directly. */
        fun Database.seedPendingUser(userId: String) {
            transaction(this) {
                UserEntity.new(userId) {
                    email = "$userId@example.com"
                    emailNormalized = "$userId@example.com"
                    passwordHash = "phc"
                    role = UserRoleColumn.MEMBER
                    displayName = userId
                    status = UserStatusColumn.PENDING_APPROVAL
                    createdAt = 1L
                    updatedAt = 1L
                }
            }
        }

        // ── Case 1: updateMyProfile triggers a refresh ────────────────────────────

        test("updateMyProfile refreshes the public_profiles projection with the new displayName") {
            withInMemoryDatabase {
                seedTestUser("u1")
                val (maintainer, repo) = buildMaintainerAndRepo()

                // Seed an initial projection row so we can verify it changes.
                runTest {
                    maintainer.refresh("u1")

                    val svc =
                        ProfileServiceImpl(
                            db = this@withInMemoryDatabase,
                            passwordHasher = PasswordHasher(),
                            publicProfileMaintainer = maintainer,
                        ).copyWith(principalFor("u1"))

                    svc
                        .updateMyProfile(UpdateProfileRequest(displayName = "New Name"))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    val row = repo.pullSince(userId = null, cursor = 0L, limit = 10).items.single()
                    row.displayName shouldBe "New Name"
                }
            }
        }

        // ── Case 2: decidePendingRegistration(approve) triggers a refresh ─────────

        test("approving a pending registration creates a public_profiles row for the new user") {
            withInMemoryDatabase {
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedPendingUser("p1")
                val (maintainer, repo) = buildMaintainerAndRepo()

                runTest {
                    val svc =
                        makeAdminUserService(maintainer)
                            .copyWith(principalFor("root1", UserRole.ROOT))

                    svc
                        .decidePendingRegistration(PendingRegistrationDecision(UserId("p1"), approved = true))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    val rows = repo.pullSince(userId = null, cursor = 0L, limit = 10).items
                    val row = rows.find { it.id == "p1" }
                    row.shouldNotBeNull()
                    row.deletedAt shouldBe null
                }
            }
        }

        // ── Case 3: deleteUser triggers a tombstone ───────────────────────────────

        test("deleteUser soft-deletes the public_profiles projection row") {
            withInMemoryDatabase {
                seedTestUser("root1", UserRoleColumn.ROOT)
                seedTestUser("m1", UserRoleColumn.MEMBER)
                val (maintainer, repo) = buildMaintainerAndRepo()

                runTest {
                    // Seed the projection row before deletion.
                    maintainer.refresh("m1")

                    val svc =
                        makeAdminUserService(maintainer)
                            .copyWith(principalFor("root1", UserRole.ROOT))

                    svc
                        .deleteUser(UserId("m1"))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    val rows = repo.pullSince(userId = null, cursor = 0L, limit = 10).items
                    val row = rows.find { it.id == "m1" }
                    row.shouldNotBeNull()
                    row.deletedAt.shouldNotBeNull()
                }
            }
        }
    })

private fun <T> AppResult<T>.shouldSucceed(): T = shouldBeInstanceOf<AppResult.Success<T>>().data

private inline fun <reified E : AppError> AppResult<*>.shouldFail(): E =
    shouldBeInstanceOf<AppResult.Failure>()
        .error
        .shouldBeInstanceOf<E>()
