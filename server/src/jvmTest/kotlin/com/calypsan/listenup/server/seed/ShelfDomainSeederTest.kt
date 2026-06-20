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
import com.calypsan.listenup.server.db.UserTable
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.ShelfRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Smoke tests for [ShelfDomainSeeder].
 *
 * Uses the real [ShelfRepository] against an in-memory SQLite DB (via Flyway) so
 * [isAlreadySeeded] and the seeded rows exercise the real schema and write-path.
 */
class ShelfDomainSeederTest :
    FunSpec({

        fun makeShelfRepo(db: Database): ShelfRepository = ShelfRepository(db.asSqlDatabase(), ChangeBus(), SyncRegistry())

        fun newAuthService(db: Database): AuthServiceImpl {
            val pepper = "x".repeat(32).toByteArray()
            val clock = FixedClock(Instant.parse("2026-06-04T12:00:00Z"))
            val hasher = PasswordHasher()
            val sessions =
                SessionService(db.asSqlDatabase(), RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
            val settings = ServerSettingsRepository(db.asSqlDatabase(), default = RegistrationPolicy.OPEN)
            return AuthServiceImpl(
                db = db.asSqlDatabase(),
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
                val repo = makeShelfRepo(this)
                val seeder = ShelfDomainSeeder(sql = this.asSqlDatabase(), shelfRepo = repo)
                runTest {
                    seeder.isAlreadySeeded() shouldBe false
                }
            }
        }

        test("seed() creates a public and a private shelf for the demo user") {
            withInMemoryDatabase {
                val db = this
                val authService = newAuthService(db)
                val repo = makeShelfRepo(db)
                val userSeeder = UserDomainSeeder(db.asSqlDatabase(), authService)
                val shelfSeeder = ShelfDomainSeeder(sql = db.asSqlDatabase(), shelfRepo = repo)

                runTest {
                    // Seed the demo user first so the demo email row exists.
                    userSeeder.seed()
                    val demoId = demoUserId(db)

                    shelfSeeder.isAlreadySeeded() shouldBe false
                    shelfSeeder.seed()

                    // Two demo shelves are created. The authService used by UserDomainSeeder
                    // in this test has no shelfRepository so no auto-created starter shelf.
                    val allShelves = repo.listOwnedBy(demoId)
                    allShelves shouldHaveSize 2

                    val public = allShelves.first { !it.isPrivate }
                    public.name shouldBe ShelfDomainSeeder.DEMO_PUBLIC_SHELF_NAME

                    val private = allShelves.first { it.isPrivate }
                    private.name shouldBe ShelfDomainSeeder.DEMO_PRIVATE_SHELF_NAME
                }
            }
        }

        test("isAlreadySeeded returns true after seed()") {
            withInMemoryDatabase {
                val db = this
                val authService = newAuthService(db)
                val repo = makeShelfRepo(db)
                val userSeeder = UserDomainSeeder(db.asSqlDatabase(), authService)
                val shelfSeeder = ShelfDomainSeeder(sql = db.asSqlDatabase(), shelfRepo = repo)

                runTest {
                    userSeeder.seed()
                    shelfSeeder.seed()
                    shelfSeeder.isAlreadySeeded() shouldBe true
                }
            }
        }

        test("seed() can be called twice without throwing") {
            withInMemoryDatabase {
                val db = this
                val authService = newAuthService(db)
                val repo = makeShelfRepo(db)
                val userSeeder = UserDomainSeeder(db.asSqlDatabase(), authService)
                val shelfSeeder = ShelfDomainSeeder(sql = db.asSqlDatabase(), shelfRepo = repo)

                runTest {
                    userSeeder.seed()
                    shelfSeeder.seed()
                    // A second call must not throw (SeedRunner guards with isAlreadySeeded,
                    // but callers should be able to call seed() repeatedly without errors).
                    shelfSeeder.seed()
                }
            }
        }

        test("domainName is shelves and order is 32") {
            withInMemoryDatabase {
                val repo = makeShelfRepo(this)
                val seeder = ShelfDomainSeeder(sql = this.asSqlDatabase(), shelfRepo = repo)
                seeder.domainName shouldBe "shelves"
                seeder.order shouldBe 32
            }
        }

        test("order is greater than CollectionDomainSeeder order (32 > 31)") {
            withInMemoryDatabase {
                val repo = makeShelfRepo(this)
                val seeder = ShelfDomainSeeder(sql = this.asSqlDatabase(), shelfRepo = repo)
                (seeder.order > 31) shouldBe true
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
