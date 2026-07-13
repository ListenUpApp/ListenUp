@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.SessionIssuer
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Smoke tests for [PublicProfileDomainSeeder].
 *
 * Uses real collaborators against an in-memory SQLite DB (via Flyway) so
 * [isAlreadySeeded] and [PublicProfileDomainSeeder.seed] exercise the real schema
 * and write-path through [PublicProfileMaintainer].
 */
class PublicProfileDomainSeederTest :
    FunSpec({

        fun makePublicProfileMaintainer(sql: ListenUpDatabase): PublicProfileMaintainer {
            val repo = PublicProfileRepository(sql, ChangeBus(), SyncRegistry())
            return PublicProfileMaintainer(sql = sql, publicProfileRepo = repo)
        }

        fun newAuthService(sql: ListenUpDatabase): AuthServiceImpl {
            val pepper = "x".repeat(32).toByteArray()
            val clock = FixedClock(Instant.parse("2026-06-04T12:00:00Z"))
            val hasher = PasswordHasher()
            val sessions =
                SessionService(sql, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
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

        test("isAlreadySeeded returns false when no demo user exists yet") {
            withSqlDatabase {
                val maintainer = makePublicProfileMaintainer(sql)
                val seeder = PublicProfileDomainSeeder(sql = sql, publicProfileMaintainer = maintainer)
                runTest {
                    seeder.isAlreadySeeded() shouldBe false
                }
            }
        }

        test("seed() creates a public_profiles row for the demo user") {
            withSqlDatabase {
                val authService = newAuthService(sql)
                val maintainer = makePublicProfileMaintainer(sql)
                val userSeeder = UserDomainSeeder(sql, authService)
                val seeder = PublicProfileDomainSeeder(sql = sql, publicProfileMaintainer = maintainer)

                runTest {
                    userSeeder.seed()
                    val demoId = demoUserId(sql)

                    seeder.isAlreadySeeded() shouldBe false
                    seeder.seed()

                    sql.publicProfilesQueries
                        .selectById(demoId)
                        .executeAsOneOrNull()
                        .shouldNotBeNull()
                    seeder.isAlreadySeeded() shouldBe true
                }
            }
        }

        test("isAlreadySeeded returns true after seed()") {
            withSqlDatabase {
                val authService = newAuthService(sql)
                val maintainer = makePublicProfileMaintainer(sql)
                val userSeeder = UserDomainSeeder(sql, authService)
                val seeder = PublicProfileDomainSeeder(sql = sql, publicProfileMaintainer = maintainer)

                runTest {
                    userSeeder.seed()
                    seeder.seed()
                    seeder.isAlreadySeeded() shouldBe true
                }
            }
        }

        test("seed() can be called twice without throwing") {
            withSqlDatabase {
                val authService = newAuthService(sql)
                val maintainer = makePublicProfileMaintainer(sql)
                val userSeeder = UserDomainSeeder(sql, authService)
                val seeder = PublicProfileDomainSeeder(sql = sql, publicProfileMaintainer = maintainer)

                runTest {
                    userSeeder.seed()
                    seeder.seed()
                    seeder.seed()
                }
            }
        }

        test("domainName is public_profiles and order is 33") {
            withSqlDatabase {
                val maintainer = makePublicProfileMaintainer(sql)
                val seeder = PublicProfileDomainSeeder(sql = sql, publicProfileMaintainer = maintainer)
                seeder.domainName shouldBe "public_profiles"
                seeder.order shouldBe 33
            }
        }

        test("order is greater than ShelfDomainSeeder order (33 > 32)") {
            withSqlDatabase {
                val maintainer = makePublicProfileMaintainer(sql)
                val seeder = PublicProfileDomainSeeder(sql = sql, publicProfileMaintainer = maintainer)
                (seeder.order > 32) shouldBe true
            }
        }
    })

private fun demoUserId(sql: ListenUpDatabase): String =
    sql.usersQueries
        .selectByEmailNormalized(UserDomainSeeder.DEMO_EMAIL)
        .executeAsOne()
        .id
