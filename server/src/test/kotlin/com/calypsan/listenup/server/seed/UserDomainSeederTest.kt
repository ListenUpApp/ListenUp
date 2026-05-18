package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class UserDomainSeederTest :
    FunSpec({
        test("seeds the demo user, who can then authenticate") {
            withInMemoryDatabase {
                val db = this
                val authService = newAuthService(db)
                val seeder = UserDomainSeeder(db, authService)

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

        test("seed() is safe to call twice — the second call does not throw") {
            withInMemoryDatabase {
                val db = this
                val authService = newAuthService(db)
                val seeder = UserDomainSeeder(db, authService)
                runTest {
                    seeder.seed()
                    seeder.seed() // setupRoot returns a Failure on the second call; seed() must swallow it
                    seeder.isAlreadySeeded() shouldBe true
                }
            }
        }
    })

private fun newAuthService(db: Database): AuthServiceImpl {
    val pepper = "x".repeat(32).toByteArray()
    val clock = FixedClock(Instant.parse("2026-05-02T12:00:00Z"))
    val hasher = PasswordHasher()
    val sessions = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
    val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
    return AuthServiceImpl(
        db = db,
        sessions = sessions,
        hasher = hasher,
        jwt = jwt,
        clock = clock,
    )
}
