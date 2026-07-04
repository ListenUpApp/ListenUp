package com.calypsan.listenup.server.seed

import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class ActivitySeederTest :
    FunSpec({
        test("no-book first run seeds nothing and re-runs next restart") {
            withSqlDatabase {
                seedDemoUser(sql)
                val seeder = ActivitySeeder(sql, activityRecorder())

                runTest {
                    seeder.isAlreadySeeded() shouldBe false
                    seeder.seed()

                    // Nothing was written, so the seeder is NOT considered done — it re-runs.
                    activityCount(sql) shouldBe 0L
                    seeder.isAlreadySeeded() shouldBe false
                }
            }
        }

        test("with a book, both rows are seeded and the seeder is then done") {
            withSqlDatabase {
                seedDemoUser(sql)
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")
                val seeder = ActivitySeeder(sql, activityRecorder())

                runTest {
                    seeder.seed()

                    // finished_book + user_joined.
                    activityCount(sql) shouldBe 2L
                    seeder.isAlreadySeeded() shouldBe true
                }
            }
        }

        test("a no-book run followed by a book run seeds both rows") {
            withSqlDatabase {
                seedDemoUser(sql)
                val seeder = ActivitySeeder(sql, activityRecorder())

                runTest {
                    // First run: no books yet — nothing seeded, still re-runnable.
                    seeder.seed()
                    seeder.isAlreadySeeded() shouldBe false

                    // Scan completes; the next restart's run seeds both rows.
                    sql.seedTestLibraryAndFolder()
                    sql.seedTestBook("book-1")
                    seeder.seed()

                    activityCount(sql) shouldBe 2L
                    seeder.isAlreadySeeded() shouldBe true
                }
            }
        }
    })

private fun seedDemoUser(sql: ListenUpDatabase) {
    // Use the exact email the ActivitySeeder looks up: UserDomainSeeder.DEMO_EMAIL.
    sql.usersQueries.insert(
        id = "demo-user",
        email = UserDomainSeeder.DEMO_EMAIL,
        email_normalized = UserDomainSeeder.DEMO_EMAIL,
        password_hash = "phc",
        role = UserRoleColumn.ROOT.name,
        display_name = "Demo",
        status = UserStatusColumn.ACTIVE.name,
        created_at = 1L,
        updated_at = 1L,
        last_login_at = null,
        can_edit = 1L,
        can_share = 1L,
        approved_by = null,
        approved_at = null,
        deleted_at = null,
        invited_by = null,
        tagline = null,
        avatar_type = "auto",
        timezone = "UTC",
    )
}

private fun activityCount(sql: ListenUpDatabase): Long = sql.activitiesQueries.countAll().executeAsOne()
