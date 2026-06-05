package com.calypsan.listenup.server.seed

import com.calypsan.listenup.server.db.ActivitiesTable
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.services.ActivityRepository
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class ActivitySeederTest :
    FunSpec({
        test("no-book first run seeds nothing and re-runs next restart") {
            withInMemoryDatabase {
                val db = this
                seedDemoUser(db)
                val seeder = ActivitySeeder(db, ActivityRepository(db))

                runTest {
                    seeder.isAlreadySeeded() shouldBe false
                    seeder.seed()

                    // Nothing was written, so the seeder is NOT considered done — it re-runs.
                    activityCount(db) shouldBe 0L
                    seeder.isAlreadySeeded() shouldBe false
                }
            }
        }

        test("with a book, both rows are seeded and the seeder is then done") {
            withInMemoryDatabase {
                val db = this
                seedDemoUser(db)
                seedTestLibraryAndFolder()
                seedTestBook("book-1")
                val seeder = ActivitySeeder(db, ActivityRepository(db))

                runTest {
                    seeder.seed()

                    // finished_book + user_joined.
                    activityCount(db) shouldBe 2L
                    seeder.isAlreadySeeded() shouldBe true
                }
            }
        }

        test("a no-book run followed by a book run seeds both rows") {
            withInMemoryDatabase {
                val db = this
                seedDemoUser(db)
                val seeder = ActivitySeeder(db, ActivityRepository(db))

                runTest {
                    // First run: no books yet — nothing seeded, still re-runnable.
                    seeder.seed()
                    seeder.isAlreadySeeded() shouldBe false

                    // Scan completes; the next restart's run seeds both rows.
                    seedTestLibraryAndFolder()
                    seedTestBook("book-1")
                    seeder.seed()

                    activityCount(db) shouldBe 2L
                    seeder.isAlreadySeeded() shouldBe true
                }
            }
        }
    })

private fun seedDemoUser(db: Database) {
    transaction(db) {
        UserEntity.new("demo-user") {
            email = UserDomainSeeder.DEMO_EMAIL
            emailNormalized = UserDomainSeeder.DEMO_EMAIL
            passwordHash = "phc"
            role = UserRoleColumn.ROOT
            displayName = "Demo"
            status = UserStatusColumn.ACTIVE
            createdAt = 1L
            updatedAt = 1L
        }
    }
}

private suspend fun activityCount(db: Database): Long =
    suspendTransaction(db) {
        ActivitiesTable.selectAll().count()
    }
