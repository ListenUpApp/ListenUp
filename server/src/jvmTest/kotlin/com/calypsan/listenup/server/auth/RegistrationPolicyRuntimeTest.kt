@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Proves the registration policy is read from [ServerSettingsRepository] at
 * registration time, so an admin's [ServerSettingsRepository.setRegistrationPolicy]
 * takes effect on the very next registration — no server restart required.
 */
class RegistrationPolicyRuntimeTest :
    FunSpec({
        test("setting CLOSED at runtime makes register fail without a restart") {
            withSqlDatabase {
                runTest {
                    val settings = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
                    val auth = makeAuthService(sql, settings)
                    auth.setupRoot(RegisterRequest("root@x.y", "x".repeat(8), "Root"))

                    settings.setRegistrationPolicy(RegistrationPolicy.CLOSED)

                    val res = auth.register(RegisterRequest("new@x.y", "x".repeat(8), "New"))
                    res.shouldBeInstanceOf<AppResult.Failure>()
                    res.error.shouldBeInstanceOf<AuthError.RegistrationDisabled>()
                }
            }
        }
    })

@OptIn(ExperimentalTime::class)
private fun makeAuthService(
    db: ListenUpDatabase,
    settings: ServerSettingsRepository,
): AuthServiceImpl {
    val pepper = "x".repeat(32).toByteArray()
    val clock = FixedClock(Instant.parse("2026-05-02T12:00:00Z"))
    val hasher = PasswordHasher()
    val sessions = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
    val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
    return AuthServiceImpl(
        db = db,
        sessions = sessions,
        hasher = Argon2Limiter(hasher),
        jwt = jwt,
        sessionIssuer = SessionIssuer(sessions, jwt, clock),
        clock = clock,
        settings = settings,
    )
}
