@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.SessionIssuer
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.db.PublicProfilesTable
import com.calypsan.listenup.server.db.UserTable
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
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

        fun makePublicProfileMaintainer(db: Database): PublicProfileMaintainer {
            val repo = PublicProfileRepository(db, ChangeBus(), SyncRegistry())
            return PublicProfileMaintainer(db = db, publicProfileRepo = repo)
        }

        fun newAuthService(db: Database): AuthServiceImpl {
            val pepper = "x".repeat(32).toByteArray()
            val clock = FixedClock(Instant.parse("2026-06-04T12:00:00Z"))
            val hasher = PasswordHasher()
            val sessions = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
            val settings = ServerSettingsRepository(db, default = RegistrationPolicy.OPEN)
            return AuthServiceImpl(
                db = db,
                sessions = sessions,
                hasher = hasher,
                jwt = jwt,
                sessionIssuer = SessionIssuer(sessions, jwt, clock),
                clock = clock,
                settings = settings,
            )
        }

        test("isAlreadySeeded returns false when no demo user exists yet") {
            withInMemoryDatabase {
                val maintainer = makePublicProfileMaintainer(this)
                val seeder = PublicProfileDomainSeeder(db = this, publicProfileMaintainer = maintainer)
                runTest {
                    seeder.isAlreadySeeded() shouldBe false
                }
            }
        }

        test("seed() creates a public_profiles row for the demo user") {
            withInMemoryDatabase {
                val db = this
                val authService = newAuthService(db)
                val maintainer = makePublicProfileMaintainer(db)
                val userSeeder = UserDomainSeeder(db, authService)
                val seeder = PublicProfileDomainSeeder(db = db, publicProfileMaintainer = maintainer)

                runTest {
                    userSeeder.seed()
                    val demoId = demoUserId(db)

                    seeder.isAlreadySeeded() shouldBe false
                    seeder.seed()

                    suspendTransaction(db) {
                        PublicProfilesTable
                            .selectAll()
                            .where { PublicProfilesTable.id eq demoId }
                            .firstOrNull()
                    }.shouldNotBeNull()
                    seeder.isAlreadySeeded() shouldBe true
                }
            }
        }

        test("isAlreadySeeded returns true after seed()") {
            withInMemoryDatabase {
                val db = this
                val authService = newAuthService(db)
                val maintainer = makePublicProfileMaintainer(db)
                val userSeeder = UserDomainSeeder(db, authService)
                val seeder = PublicProfileDomainSeeder(db = db, publicProfileMaintainer = maintainer)

                runTest {
                    userSeeder.seed()
                    seeder.seed()
                    seeder.isAlreadySeeded() shouldBe true
                }
            }
        }

        test("seed() can be called twice without throwing") {
            withInMemoryDatabase {
                val db = this
                val authService = newAuthService(db)
                val maintainer = makePublicProfileMaintainer(db)
                val userSeeder = UserDomainSeeder(db, authService)
                val seeder = PublicProfileDomainSeeder(db = db, publicProfileMaintainer = maintainer)

                runTest {
                    userSeeder.seed()
                    seeder.seed()
                    seeder.seed()
                }
            }
        }

        test("domainName is public_profiles and order is 33") {
            withInMemoryDatabase {
                val maintainer = makePublicProfileMaintainer(this)
                val seeder = PublicProfileDomainSeeder(db = this, publicProfileMaintainer = maintainer)
                seeder.domainName shouldBe "public_profiles"
                seeder.order shouldBe 33
            }
        }

        test("order is greater than ShelfDomainSeeder order (33 > 32)") {
            withInMemoryDatabase {
                val maintainer = makePublicProfileMaintainer(this)
                val seeder = PublicProfileDomainSeeder(db = this, publicProfileMaintainer = maintainer)
                (seeder.order > 32) shouldBe true
            }
        }
    })

private suspend fun demoUserId(db: Database): String =
    suspendTransaction(db) {
        UserTable
            .selectAll()
            .where { UserTable.email eq UserDomainSeeder.DEMO_EMAIL }
            .first()
            .get(UserTable.id)
            .value
    }
