package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.profile.PasswordChange
import com.calypsan.listenup.api.dto.profile.Profile
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import com.calypsan.listenup.api.error.ProfileError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.noOpPublicProfileMaintainer
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Instant

class ProfileServiceImplTest :
    FunSpec({
        fun Database.svc(userId: String): ProfileServiceImpl =
            ProfileServiceImpl(
                sql = this.asSqlDatabase(),
                passwordHasher = PasswordHasher(),
                publicProfileMaintainer = noOpPublicProfileMaintainer(),
            ).copyWith(
                PrincipalProvider {
                    UserPrincipal(UserId(userId), SessionId("s-$userId"), UserRole.MEMBER)
                },
            )

        test("getMyProfile returns the caller's profile") {
            withInMemoryDatabase {
                seedTestUser("u1")
                runTest { svc("u1").getMyProfile().shouldBeInstanceOf<AppResult.Success<Profile>>() }
            }
        }

        test("updateMyProfile stamps updatedAt from the injected clock") {
            withInMemoryDatabase {
                seedTestUser("u1")
                runTest {
                    val fixed = 1_700_000_000_000L
                    val svc =
                        ProfileServiceImpl(
                            sql = this@withInMemoryDatabase.asSqlDatabase(),
                            passwordHasher = PasswordHasher(),
                            publicProfileMaintainer = this@withInMemoryDatabase.noOpPublicProfileMaintainer(),
                            clock = FixedClock(Instant.fromEpochMilliseconds(fixed)),
                        ).copyWith(
                            PrincipalProvider {
                                UserPrincipal(UserId("u1"), SessionId("s"), UserRole.MEMBER)
                            },
                        )
                    val r = svc.updateMyProfile(UpdateProfileRequest(displayName = "New Name"))
                    r as AppResult.Success
                    r.data.updatedAt shouldBe fixed
                }
            }
        }

        test("updateMyProfile updates displayName + tagline; leaves others") {
            withInMemoryDatabase {
                seedTestUser("u1")
                runTest {
                    val r =
                        svc("u1")
                            .updateMyProfile(UpdateProfileRequest(displayName = "New Name", tagline = "Hi"))
                    r as AppResult.Success
                    r.data.displayName shouldBe "New Name"
                    r.data.tagline shouldBe "Hi"
                }
            }
        }

        test("updateMyProfile happy-path password change works with correct current password") {
            withInMemoryDatabase {
                val hasher = PasswordHasher()
                runTest {
                    val hash = hasher.hash("correct-pass")
                    seedTestUser("u1")
                    // Override the placeholder hash with a real Argon2 hash so verify() can succeed.
                    transaction(this@withInMemoryDatabase) {
                        UserEntity.findById("u1")!!.passwordHash = hash
                    }
                    val svc =
                        ProfileServiceImpl(
                            sql = this@withInMemoryDatabase.asSqlDatabase(),
                            passwordHasher = hasher,
                            publicProfileMaintainer = this@withInMemoryDatabase.noOpPublicProfileMaintainer(),
                        ).copyWith(
                            PrincipalProvider {
                                UserPrincipal(UserId("u1"), SessionId("s"), UserRole.MEMBER)
                            },
                        )
                    val r =
                        svc.updateMyProfile(
                            UpdateProfileRequest(
                                password = PasswordChange("correct-pass", "brand-new-pass"),
                            ),
                        )
                    r.shouldBeInstanceOf<AppResult.Success<Profile>>()
                }
            }
        }

        test("updateMyProfile with wrong current password returns WrongPassword") {
            withInMemoryDatabase {
                val hasher = PasswordHasher()
                runTest {
                    val hash = hasher.hash("correct-pass")
                    seedTestUser("u1")
                    // Override the placeholder hash with a real Argon2 hash so verify() can succeed.
                    transaction(this@withInMemoryDatabase) {
                        UserEntity.findById("u1")!!.passwordHash = hash
                    }
                    val svc =
                        ProfileServiceImpl(
                            sql = this@withInMemoryDatabase.asSqlDatabase(),
                            passwordHasher = hasher,
                            publicProfileMaintainer = this@withInMemoryDatabase.noOpPublicProfileMaintainer(),
                        ).copyWith(
                            PrincipalProvider {
                                UserPrincipal(UserId("u1"), SessionId("s"), UserRole.MEMBER)
                            },
                        )
                    val r =
                        svc.updateMyProfile(
                            UpdateProfileRequest(
                                password = PasswordChange("wrong-current", "brand-new-pass"),
                            ),
                        )
                    val failure = r.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ProfileError.WrongPassword>()
                }
            }
        }
    })
