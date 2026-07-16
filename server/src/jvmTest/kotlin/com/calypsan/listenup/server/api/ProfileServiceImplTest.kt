package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.WeakPasswordReason
import com.calypsan.listenup.api.dto.profile.PasswordChange
import com.calypsan.listenup.api.dto.profile.Profile
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ProfileError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.noOpPublicProfileMaintainer
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.tempAvatarImageStore
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

class ProfileServiceImplTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()

        fun SqlTestDatabases.sessionsService(): SessionService = SessionService(sql, RefreshTokenHasher(pepper), RefreshTokenGenerator())

        fun SqlTestDatabases.svc(userId: String): ProfileServiceImpl =
            ProfileServiceImpl(
                sql = sql,
                argon2Limiter = Argon2Limiter(PasswordHasher()),
                publicProfileMaintainer = sql.noOpPublicProfileMaintainer(),
                imageStore = tempAvatarImageStore(),
                sessions = sessionsService(),
            ).copyWith(
                PrincipalProvider {
                    UserPrincipal(UserId(userId), SessionId("s-$userId"), UserRole.MEMBER)
                },
            )

        test("getMyProfile returns the caller's profile") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                runTest { svc("u1").getMyProfile().shouldBeInstanceOf<AppResult.Success<Profile>>() }
            }
        }

        test("updateMyProfile stamps updatedAt from the injected clock") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                runTest {
                    val fixed = 1_700_000_000_000L
                    val svc =
                        ProfileServiceImpl(
                            sql = sql,
                            argon2Limiter = Argon2Limiter(PasswordHasher()),
                            publicProfileMaintainer = sql.noOpPublicProfileMaintainer(),
                            imageStore = tempAvatarImageStore(),
                            sessions = sessionsService(),
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
            withSqlDatabase {
                sql.seedTestUser("u1")
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
            withSqlDatabase {
                val hasher = PasswordHasher()
                runTest {
                    val hash = hasher.hash("correct-pass")
                    sql.seedTestUser("u1")
                    // Override the placeholder hash with a real Argon2 hash so verify() can succeed.
                    sql.usersQueries.updatePasswordHash(password_hash = hash, id = "u1")
                    val svc =
                        ProfileServiceImpl(
                            sql = sql,
                            argon2Limiter = Argon2Limiter(hasher),
                            publicProfileMaintainer = sql.noOpPublicProfileMaintainer(),
                            imageStore = tempAvatarImageStore(),
                            sessions = sessionsService(),
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
            withSqlDatabase {
                val hasher = PasswordHasher()
                runTest {
                    val hash = hasher.hash("correct-pass")
                    sql.seedTestUser("u1")
                    // Override the placeholder hash with a real Argon2 hash so verify() can succeed.
                    sql.usersQueries.updatePasswordHash(password_hash = hash, id = "u1")
                    val svc =
                        ProfileServiceImpl(
                            sql = sql,
                            argon2Limiter = Argon2Limiter(hasher),
                            publicProfileMaintainer = sql.noOpPublicProfileMaintainer(),
                            imageStore = tempAvatarImageStore(),
                            sessions = sessionsService(),
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

        test("updateMyProfile rejects a whitespace-only new password with WeakPassword(BLANK)") {
            withSqlDatabase {
                val hasher = PasswordHasher()
                runTest {
                    val hash = hasher.hash("correct-pass")
                    sql.seedTestUser("u1")
                    sql.usersQueries.updatePasswordHash(password_hash = hash, id = "u1")
                    val svc =
                        ProfileServiceImpl(
                            sql = sql,
                            argon2Limiter = Argon2Limiter(hasher),
                            publicProfileMaintainer = sql.noOpPublicProfileMaintainer(),
                            imageStore = tempAvatarImageStore(),
                            sessions = sessionsService(),
                        ).copyWith(
                            PrincipalProvider {
                                UserPrincipal(UserId("u1"), SessionId("s"), UserRole.MEMBER)
                            },
                        )
                    val r =
                        svc.updateMyProfile(
                            UpdateProfileRequest(
                                password = PasswordChange("correct-pass", " ".repeat(8)),
                            ),
                        )
                    val failure = r.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<AuthError.WeakPassword>().reason shouldBe WeakPasswordReason.BLANK
                }
            }
        }

        test("a password change routes hash + verify through the Argon2Limiter (bounded, not the raw hasher)") {
            withSqlDatabase {
                val hasher = PasswordHasher()
                var hashCalls = 0
                var verifyCalls = 0
                // A limiter wrapping counting stand-ins over the real hasher — proves ProfileServiceImpl's
                // Argon2 goes through the shared gate (C3 DoS ceiling) instead of a raw PasswordHasher.
                val counting =
                    Argon2Limiter(
                        permits = 1,
                        hashFn = { plaintext ->
                            hashCalls++
                            hasher.hash(plaintext)
                        },
                        verifyFn = { plaintext, encoded ->
                            verifyCalls++
                            hasher.verify(plaintext, encoded)
                        },
                    )
                runTest {
                    val hash = hasher.hash("correct-pass")
                    sql.seedTestUser("u1")
                    sql.usersQueries.updatePasswordHash(password_hash = hash, id = "u1")
                    val svc =
                        ProfileServiceImpl(
                            sql = sql,
                            argon2Limiter = counting,
                            publicProfileMaintainer = sql.noOpPublicProfileMaintainer(),
                            imageStore = tempAvatarImageStore(),
                            sessions = sessionsService(),
                        ).copyWith(
                            PrincipalProvider {
                                UserPrincipal(UserId("u1"), SessionId("s"), UserRole.MEMBER)
                            },
                        )
                    svc
                        .updateMyProfile(
                            UpdateProfileRequest(
                                password = PasswordChange("correct-pass", "brand-new-pass"),
                            ),
                        ).shouldBeInstanceOf<AppResult.Success<Profile>>()
                    // Both the current-password verify and the new-password hash went through the gate.
                    verifyCalls shouldBe 1
                    hashCalls shouldBe 1
                }
            }
        }

        test("reverting to the auto avatar advances public_profiles.avatarUpdatedAt and deletes the bytes") {
            withSqlDatabase {
                // A user who previously uploaded an image avatar, stamped at t1.
                val t1 = 1_700_000_000_000L
                val t2 = t1 + 60_000L
                sql.seedTestUser("u1")
                sql.usersQueries.updateAvatarType(
                    avatar_type = "image",
                    avatar_updated_at = t1,
                    updated_at = t1,
                    id = "u1",
                )
                // A minimal valid PNG so the store actually holds bytes to delete.
                val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
                val imageStore = tempAvatarImageStore()
                runTest {
                    imageStore.store("u1", png, "image/png")
                    val svc =
                        ProfileServiceImpl(
                            sql = sql,
                            argon2Limiter = Argon2Limiter(PasswordHasher()),
                            publicProfileMaintainer = sql.noOpPublicProfileMaintainer(),
                            imageStore = imageStore,
                            sessions = sessionsService(),
                            clock = FixedClock(Instant.fromEpochMilliseconds(t2)),
                        ).copyWith(
                            PrincipalProvider { UserPrincipal(UserId("u1"), SessionId("s"), UserRole.MEMBER) },
                        )

                    svc.updateMyProfile(UpdateProfileRequest(avatarType = "auto")) as AppResult.Success

                    // The projection every client syncs must carry the advanced avatar version, or the
                    // revert silently never busts the cached bitmap on any device.
                    val row = sql.publicProfilesQueries.selectByIds(listOf("u1")).executeAsOneOrNull()
                    row?.avatar_updated_at shouldBe t2
                    // And the orphaned bytes are gone so GET /avatars/{id} 404s.
                    imageStore.pathFor("u1").shouldBeNull()
                }
            }
        }

        // ── Session revocation on password change (SEC-03) ─────────────────────────

        test("a successful password change revokes the caller's OTHER sessions but spares the current one") {
            withSqlDatabase {
                val hasher = PasswordHasher()
                runTest {
                    val hash = hasher.hash("correct-pass")
                    sql.seedTestUser("u1")
                    sql.usersQueries.updatePasswordHash(password_hash = hash, id = "u1")
                    val sessions = sessionsService()
                    // The session issuing THIS request, plus one other live session on a different device.
                    val current = sessions.createSession(UserId("u1"))
                    val other = sessions.createSession(UserId("u1"))

                    val svc =
                        ProfileServiceImpl(
                            sql = sql,
                            argon2Limiter = Argon2Limiter(hasher),
                            publicProfileMaintainer = sql.noOpPublicProfileMaintainer(),
                            imageStore = tempAvatarImageStore(),
                            sessions = sessions,
                        ).copyWith(
                            PrincipalProvider {
                                UserPrincipal(UserId("u1"), current.sessionId, UserRole.MEMBER)
                            },
                        )

                    svc
                        .updateMyProfile(UpdateProfileRequest(password = PasswordChange("correct-pass", "brand-new-pass")))
                        .shouldBeInstanceOf<AppResult.Success<Profile>>()

                    sessions.isLive(current.sessionId) shouldBe true
                    sessions.isLive(other.sessionId) shouldBe false
                }
            }
        }

        test("a display-name-only update does NOT revoke any sessions") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                runTest {
                    val sessions = sessionsService()
                    val current = sessions.createSession(UserId("u1"))
                    val other = sessions.createSession(UserId("u1"))

                    val svc =
                        ProfileServiceImpl(
                            sql = sql,
                            argon2Limiter = Argon2Limiter(PasswordHasher()),
                            publicProfileMaintainer = sql.noOpPublicProfileMaintainer(),
                            imageStore = tempAvatarImageStore(),
                            sessions = sessions,
                        ).copyWith(
                            PrincipalProvider {
                                UserPrincipal(UserId("u1"), current.sessionId, UserRole.MEMBER)
                            },
                        )

                    svc
                        .updateMyProfile(UpdateProfileRequest(displayName = "New Name"))
                        .shouldBeInstanceOf<AppResult.Success<Profile>>()

                    sessions.isLive(current.sessionId) shouldBe true
                    sessions.isLive(other.sessionId) shouldBe true
                }
            }
        }

        test("a failed password change (wrong current password) does NOT revoke any sessions") {
            withSqlDatabase {
                val hasher = PasswordHasher()
                runTest {
                    val hash = hasher.hash("correct-pass")
                    sql.seedTestUser("u1")
                    sql.usersQueries.updatePasswordHash(password_hash = hash, id = "u1")
                    val sessions = sessionsService()
                    val current = sessions.createSession(UserId("u1"))
                    val other = sessions.createSession(UserId("u1"))

                    val svc =
                        ProfileServiceImpl(
                            sql = sql,
                            argon2Limiter = Argon2Limiter(hasher),
                            publicProfileMaintainer = sql.noOpPublicProfileMaintainer(),
                            imageStore = tempAvatarImageStore(),
                            sessions = sessions,
                        ).copyWith(
                            PrincipalProvider {
                                UserPrincipal(UserId("u1"), current.sessionId, UserRole.MEMBER)
                            },
                        )

                    svc
                        .updateMyProfile(UpdateProfileRequest(password = PasswordChange("wrong-current", "brand-new-pass")))
                        .shouldBeInstanceOf<AppResult.Failure>()

                    sessions.isLive(current.sessionId) shouldBe true
                    sessions.isLive(other.sessionId) shouldBe true
                }
            }
        }
    })
