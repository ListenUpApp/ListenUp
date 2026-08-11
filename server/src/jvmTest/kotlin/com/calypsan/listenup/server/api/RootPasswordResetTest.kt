@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.RootResetToken
import com.calypsan.listenup.server.auth.SessionIssuer
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.RootPasswordResetService
import com.calypsan.listenup.server.services.SessionRevoker
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.migratedTestDatabase
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.testPasswordResetService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

private const val NEW_PASSWORD = "a-strong-new-root-password"

/**
 * RPC-level tests for [AuthServiceImpl.resetRootPassword] — the root escape hatch. Real
 * in-memory migrated SQLite; no mocks. Each test wires its own [RootPasswordResetService] with a
 * chosen [RootResetToken] state (disarmed / wrong-token / expired-window / freshly-armed) so a
 * single spec can exercise every branch of the "identical error" contract.
 */
class RootPasswordResetTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()
        val start = Instant.fromEpochMilliseconds(1_700_000_000_000)

        fun newSvc(
            db: ListenUpDatabase,
            rootResetToken: RootResetToken,
            svcClock: Clock = FixedClock(start),
        ): AuthServiceImpl {
            val hasher = Argon2Limiter(PasswordHasher())
            val sessions =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = svcClock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, svcClock)
            val settings = ServerSettingsRepository(db, default = RegistrationPolicy.OPEN)
            return AuthServiceImpl(
                db = db,
                sessions = sessions,
                hasher = hasher,
                jwt = jwt,
                sessionIssuer = SessionIssuer(sessions, jwt, svcClock),
                clock = svcClock,
                settings = settings,
                passwordResetService = testPasswordResetService(db, svcClock),
                rootPasswordResetService =
                    RootPasswordResetService(
                        db = db,
                        passwords = hasher,
                        sessions = SessionRevoker { sessions.revokeAll(it) },
                        clock = svcClock,
                        rootResetToken = rootResetToken,
                    ),
            )
        }

        test("disarmed, a wrong token, and an expired window all fail with the identical error") {
            val db = migratedTestDatabase().db
            db.seedTestUser("root1", UserRoleColumn.ROOT)

            val disarmedSvc = newSvc(db, RootResetToken.disarmed())
            val wrongTokenSvc = newSvc(db, RootResetToken.armed(FixedClock(start)))
            // Armed at `start`, but the service's own clock has already moved 16 minutes past
            // it — the WINDOW (15 minutes) has closed.
            val expiredToken = RootResetToken.armed(FixedClock(start))
            val expiredSvc = newSvc(db, expiredToken, svcClock = FixedClock(start.plus(16.minutes)))

            runTest {
                val disarmedResult = disarmedSvc.resetRootPassword("anything", NEW_PASSWORD)
                val wrongTokenResult = wrongTokenSvc.resetRootPassword("definitely-not-it", NEW_PASSWORD)
                val expiredResult = expiredSvc.resetRootPassword(expiredToken.token, NEW_PASSWORD)

                val disarmedError = disarmedResult.shouldBeInstanceOf<AppResult.Failure>().error
                val wrongTokenError = wrongTokenResult.shouldBeInstanceOf<AppResult.Failure>().error
                val expiredError = expiredResult.shouldBeInstanceOf<AppResult.Failure>().error

                disarmedError.shouldBeInstanceOf<AuthError.RootResetUnavailable>()
                wrongTokenError.shouldBeInstanceOf<AuthError.RootResetUnavailable>()
                expiredError.shouldBeInstanceOf<AuthError.RootResetUnavailable>()

                // The point of the shared error type: all three are byte-for-byte the same shape.
                disarmedError.code shouldBe wrongTokenError.code
                wrongTokenError.code shouldBe expiredError.code
                disarmedError.message shouldBe wrongTokenError.message
                wrongTokenError.message shouldBe expiredError.message
            }
        }

        test("a consumed token cannot be reused") {
            val db = migratedTestDatabase().db
            db.seedTestUser("root1", UserRoleColumn.ROOT)
            val token = RootResetToken.armed(FixedClock(start))
            val svc = newSvc(db, token)

            runTest {
                svc.resetRootPassword(token.token, NEW_PASSWORD).shouldBeInstanceOf<AppResult.Success<Unit>>()

                val replay = svc.resetRootPassword(token.token, "a-different-strong-password")
                replay.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AuthError.RootResetUnavailable>()
            }
        }

        // Each of these three assertions rebuilds AuthServiceImpl through a different copy
        // method. `rootPasswordResetService` has a *default* (a disarmed token) — so if any one
        // copy method dropped `rootPasswordResetService = rootPasswordResetService` from its
        // constructor call, the copy would silently fall back to that default. Every real RPC
        // call goes through `withRemoteHost` (see AuthServicePublic's registration in
        // RpcRoutes.jvm.kt/RpcRoutes.linux.kt), so that regression would permanently disarm the
        // hatch on all production traffic — and the failure is `RootResetUnavailable`, which this
        // design deliberately makes indistinguishable from "wrong token". Covering all three
        // (not just `withRemoteHost`) closes the same silent-regression risk on `copyWith` and
        // `withUserAgent`. A fresh armed token per case, since a token is single-use.
        test("copyWith preserves the wired rootPasswordResetService") {
            val db = migratedTestDatabase().db
            db.seedTestUser("root1", UserRoleColumn.ROOT)
            val token = RootResetToken.armed(FixedClock(start))
            val svc = newSvc(db, token).copyWith(PrincipalProvider { null })

            runTest {
                svc.resetRootPassword(token.token, NEW_PASSWORD).shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("withUserAgent preserves the wired rootPasswordResetService") {
            val db = migratedTestDatabase().db
            db.seedTestUser("root1", UserRoleColumn.ROOT)
            val token = RootResetToken.armed(FixedClock(start))
            val svc = newSvc(db, token).withUserAgent("test-agent")

            runTest {
                svc.resetRootPassword(token.token, NEW_PASSWORD).shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("withRemoteHost preserves the wired rootPasswordResetService") {
            val db = migratedTestDatabase().db
            db.seedTestUser("root1", UserRoleColumn.ROOT)
            val token = RootResetToken.armed(FixedClock(start))
            val svc = newSvc(db, token).withRemoteHost("127.0.0.1")

            runTest {
                svc.resetRootPassword(token.token, NEW_PASSWORD).shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("a weak password still burns the token — consume runs before validation") {
            val db = migratedTestDatabase().db
            db.seedTestUser("root1", UserRoleColumn.ROOT)
            val token = RootResetToken.armed(FixedClock(start))
            val svc = newSvc(db, token)

            runTest {
                // Below PASSWORD_MIN — PasswordPolicy.validate rejects it.
                val weak = svc.resetRootPassword(token.token, "weak")
                weak.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AuthError.WeakPassword>()

                // This only holds if consume() ran BEFORE validate() — the documented trade-off
                // (a rejected weak password still burns the one-time token). If the order were
                // ever reversed, this retry would succeed instead of failing.
                val retry = svc.resetRootPassword(token.token, NEW_PASSWORD)
                retry.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AuthError.RootResetUnavailable>()
            }
        }

        test("a successful root reset revokes root's sessions") {
            val db = migratedTestDatabase().db
            db.seedTestUser("root1", UserRoleColumn.ROOT)
            val token = RootResetToken.armed(FixedClock(start))
            val svcClock = FixedClock(start)
            val sessions =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = svcClock)
            val hasher = Argon2Limiter(PasswordHasher())
            val svc =
                AuthServiceImpl(
                    db = db,
                    sessions = sessions,
                    hasher = hasher,
                    jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, svcClock),
                    sessionIssuer =
                        SessionIssuer(
                            sessions,
                            JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, svcClock),
                            svcClock,
                        ),
                    clock = svcClock,
                    settings = ServerSettingsRepository(db, default = RegistrationPolicy.OPEN),
                    passwordResetService = testPasswordResetService(db, svcClock),
                    rootPasswordResetService =
                        RootPasswordResetService(
                            db = db,
                            passwords = hasher,
                            sessions = SessionRevoker { sessions.revokeAll(it) },
                            clock = svcClock,
                            rootResetToken = token,
                        ),
                )

            runTest {
                sessions.createSession(UserId("root1"), label = "phone")
                sessions.createSession(UserId("root1"), label = "laptop")
                sessions.listActiveFor(UserId("root1")).size shouldBe 2

                svc.resetRootPassword(token.token, NEW_PASSWORD).shouldBeInstanceOf<AppResult.Success<Unit>>()

                sessions.listActiveFor(UserId("root1")).shouldBeEmpty()
            }
        }
    })
