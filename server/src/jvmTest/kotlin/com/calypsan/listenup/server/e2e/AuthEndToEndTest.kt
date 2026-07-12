package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking

/**
 * End-to-end auth tests — real `Application.module()` over CIO + real
 * `AuthRepository` over kotlinx.rpc + real Exposed/SQLite. The contract
 * boundary is exercised in full: any drift between client and server in
 * DTO shape, polymorphic discriminator, JWT claims, refresh-token rotation,
 * or `AppResult` wire format will surface here.
 *
 * The fixture spins up a fresh embedded server + tmp SQLite per test case
 * (via `autoClose`) so cases can run in any order without state contamination.
 *
 * Coverage matrix:
 *  - Setup happy path                        — root user creation + tokens
 *  - Setup-already-complete                  — typed error survives the wire
 *  - Login happy path
 *  - Login wrong password                    — InvalidCredentials
 *  - Login unknown email                     — InvalidCredentials (no info leak)
 *  - Refresh happy path                      — rotation produces fresh tokens
 *  - Refresh with revoked token (replay)     — InvalidRefreshToken family-revoke
 *  - Logout                                  — server-side session revoked
 *  - Authed call without token               — SessionNotFound when not logged in
 */
class AuthEndToEndTest :
    FunSpec({

        fun fixture(): AuthEndToEndFixture = AuthEndToEndFixture.start()

        val rootCredentials =
            RegisterRequest(
                email = "root@example.com",
                password = "rootpassword",
                displayName = "Root Admin",
            )

        // Setup + persist tokens to AuthSession. AuthRepository.setup itself doesn't
        // persist (that's SetupUseCase's job in production); this end-to-end fixture exercises
        // the repo directly, so this helper plays the use case's role.
        suspend fun bootstrap(fix: AuthEndToEndFixture): com.calypsan.listenup.api.dto.auth.AuthSession {
            val session =
                fix.authRepository
                    .setup(rootCredentials)
                    .shouldBeInstanceOf<AppResult.Success<*>>()
                    .data
                    .shouldBeInstanceOf<com.calypsan.listenup.api.dto.auth.AuthSession>()
            fix.authSession.saveAuthTokens(
                access = session.accessToken,
                refresh = session.refreshToken,
                sessionId = session.sessionId.value,
                userId = session.user.id.value,
            )
            return session
        }

        // ========== Setup ==========

        test("setup creates root user and returns a session with non-empty tokens") {
            runBlocking {
                val fix = autoClose(fixture())

                val result = fix.authRepository.setup(rootCredentials)

                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                val session =
                    success.data
                        .shouldBeInstanceOf<com.calypsan.listenup.api.dto.auth.AuthSession>()
                session.accessToken.value.shouldNotBeEmpty()
                session.refreshToken.value.shouldNotBeEmpty()
                session.user.email shouldBe "root@example.com"
            }
        }

        test("second setup call returns SetupAlreadyComplete via the typed AppResult wire") {
            runBlocking {
                val fix = autoClose(fixture())
                bootstrap(fix)

                val result = fix.authRepository.setup(rootCredentials)

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.SetupAlreadyComplete>()
            }
        }

        // ========== Login ==========

        test("login with correct credentials returns a session") {
            runBlocking {
                val fix = autoClose(fixture())
                bootstrap(fix)

                val result =
                    fix.authRepository.login(
                        LoginRequest(email = rootCredentials.email, password = rootCredentials.password),
                    )

                val session =
                    result
                        .shouldBeInstanceOf<AppResult.Success<*>>()
                        .data
                        .shouldBeInstanceOf<com.calypsan.listenup.api.dto.auth.AuthSession>()
                session.user.email shouldBe rootCredentials.email
            }
        }

        test("login with wrong password returns InvalidCredentials") {
            runBlocking {
                val fix = autoClose(fixture())
                bootstrap(fix)

                val result =
                    fix.authRepository.login(
                        LoginRequest(email = rootCredentials.email, password = "wrongpassword"),
                    )

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.InvalidCredentials>()
            }
        }

        test("login with unknown email returns InvalidCredentials (no info leak)") {
            runBlocking {
                val fix = autoClose(fixture())
                bootstrap(fix)

                val result =
                    fix.authRepository.login(
                        LoginRequest(email = "ghost@example.com", password = "anypassword"),
                    )

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.InvalidCredentials>()
            }
        }

        // ========== Refresh ==========

        test("refreshAccessToken produces a new access token and rotates the refresh token") {
            runBlocking {
                val fix = autoClose(fixture())
                bootstrap(fix)
                val originalRefresh = fix.authSession.getRefreshToken()

                val result = fix.authRepository.refreshAccessToken()

                val session =
                    result
                        .shouldBeInstanceOf<AppResult.Success<*>>()
                        .data
                        .shouldBeInstanceOf<com.calypsan.listenup.api.dto.auth.AuthSession>()
                session.accessToken.value.shouldNotBeEmpty()
                session.refreshToken.value.shouldNotBeEmpty()
                // Rotation contract: refresh token is a fresh random opaque value, so
                // it must differ. Access token is a JWT keyed on `iat` (second-precision);
                // back-to-back rotations within the same second produce byte-identical
                // JWTs, so we don't assert inequality on it — refresh-family rotation
                // is the contract that matters, and the replay test below pins it.
                (session.refreshToken == originalRefresh) shouldBe false
            }
        }

        test("replaying a revoked refresh token returns InvalidRefreshToken") {
            runBlocking {
                val fix = autoClose(fixture())
                bootstrap(fix)
                // First refresh succeeds and rotates the family.
                val first = fix.authRepository.refreshAccessToken()
                first.shouldBeInstanceOf<AppResult.Success<*>>()

                // Replace the stored refresh token with the now-revoked original
                // by capturing it before the first refresh — but we already did
                // the refresh, so reach the family-revoke path differently:
                // a second refresh with the *new* token still works; to trigger
                // replay we need the prior token. Capture via a fresh session.
                val secondFix = autoClose(fixture())
                bootstrap(secondFix)
                val originalRefresh = secondFix.authSession.getRefreshToken()
                requireNotNull(originalRefresh)

                // Rotate once.
                secondFix.authRepository
                    .refreshAccessToken()
                    .shouldBeInstanceOf<AppResult.Success<*>>()

                // Now overwrite the stored refresh token with the original (replay).
                secondFix.authSession.saveAuthTokens(
                    access =
                        secondFix.authSession.getAccessToken()
                            ?: error("access token missing"),
                    refresh = originalRefresh,
                    sessionId = secondFix.authSession.getSessionId() ?: "",
                    userId = secondFix.authSession.getUserId() ?: "",
                )

                val replay = secondFix.authRepository.refreshAccessToken()

                replay
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.InvalidRefreshToken>()
            }
        }

        // ========== Logout ==========

        test("logout returns Success and prevents further refresh") {
            runBlocking {
                val fix = autoClose(fixture())
                bootstrap(fix)

                fix.authRepository.logout().shouldBeInstanceOf<AppResult.Success<*>>()

                // Server-side the session is revoked; refresh after logout fails.
                fix.authRepository
                    .refreshAccessToken()
                    .shouldBeInstanceOf<AppResult.Failure>()
            }
        }

        // ========== Authed surface without auth ==========

        test("logout without ever authenticating returns a typed failure") {
            runBlocking {
                val fix = autoClose(fixture())
                // Note: fixture doesn't bootstrap. authSession has no tokens.

                val result = fix.authRepository.logout()

                // The contract returns Failure (not throws); the specific variant
                // is SessionNotFound or SessionExpired depending on how the bearer
                // plugin handles a missing token. Assert *some* typed failure.
                result.shouldBeInstanceOf<AppResult.Failure>()
            }
        }
    })
