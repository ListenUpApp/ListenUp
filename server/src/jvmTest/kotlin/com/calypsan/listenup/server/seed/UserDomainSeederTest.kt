package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.SessionIssuer
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class UserDomainSeederTest :
    FunSpec({
        test("seeds the demo user, who can then authenticate") {
            withSqlDatabase {
                val authService = newAuthService(sql)
                val seeder = UserDomainSeeder(sql, authService)

                runTest {
                    seeder.isAlreadySeeded() shouldBe false
                    seeder.seed()
                    seeder.isAlreadySeeded() shouldBe true

                    val login =
                        authService.login(
                            LoginRequest(
                                email = UserDomainSeeder.DEMO_EMAIL,
                                password = UserDomainSeeder.DEMO_PASSWORD,
                            ),
                        )
                    login.shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }

        test("seeds a normal member and a permission-restricted member") {
            withSqlDatabase {
                val authService = newAuthService(sql)
                val seeder = UserDomainSeeder(sql, authService)

                runTest {
                    seeder.seed()

                    // The normal member can authenticate and keeps the default flags.
                    val memberLogin =
                        authService.login(
                            LoginRequest(
                                email = UserDomainSeeder.MEMBER_EMAIL,
                                password = UserDomainSeeder.DEMO_PASSWORD,
                            ),
                        )
                    memberLogin.shouldBeInstanceOf<AppResult.Success<*>>()

                    val member = findUser(sql, UserDomainSeeder.MEMBER_EMAIL)
                    (member.can_edit == 1L) shouldBe true
                    (member.can_share == 1L) shouldBe true

                    // The restricted member has canEdit/canShare revoked.
                    val restricted = findUser(sql, UserDomainSeeder.RESTRICTED_EMAIL)
                    (restricted.can_edit == 1L) shouldBe false
                    (restricted.can_share == 1L) shouldBe false
                }
            }
        }

        test("seed() is safe to call twice — the second call does not throw") {
            withSqlDatabase {
                val authService = newAuthService(sql)
                val seeder = UserDomainSeeder(sql, authService)
                runTest {
                    seeder.seed()
                    seeder.seed() // setupRoot returns a Failure on the second call; seed() must swallow it
                    seeder.isAlreadySeeded() shouldBe true
                }
            }
        }
    })

private fun findUser(
    sql: ListenUpDatabase,
    emailNormalized: String,
) = sql.usersQueries
    .selectByEmailNormalized(emailNormalized)
    .executeAsOneOrNull()
    .shouldNotBeNull()

private fun newAuthService(sql: ListenUpDatabase): AuthServiceImpl {
    val pepper = "x".repeat(32).toByteArray()
    val clock = FixedClock(Instant.parse("2026-05-02T12:00:00Z"))
    val hasher = PasswordHasher()
    val sessions = SessionService(sql, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
    val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
    val settings = ServerSettingsRepository(sql, default = RegistrationPolicy.OPEN)
    return AuthServiceImpl(
        db = sql,
        sessions = sessions,
        hasher = Argon2Limiter(hasher),
        jwt = jwt,
        sessionIssuer = SessionIssuer(sessions, jwt, clock),
        clock = clock,
        settings = settings,
    )
}
