package com.calypsan.listenup.server.seed

import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Smoke tests for [MoodDomainSeeder].
 *
 * Uses the real [MoodRepository] against an in-memory SQLite DB (via Flyway) so
 * the [isAlreadySeeded] check and the seeded rows exercise the real schema and
 * write-path.
 */
class MoodDomainSeederTest :
    FunSpec({

        // The canonical Audible mood vocabulary count seeded by MoodDomainSeeder.
        val expectedMoodCount = 24

        fun makeMoodRepo(db: Database): MoodRepository = MoodRepository(db.asSqlDatabase(), ChangeBus(), SyncRegistry())

        test("isAlreadySeeded returns false when no moods exist") {
            withInMemoryDatabase {
                val repo = makeMoodRepo(this)
                val seeder = MoodDomainSeeder(db = this, moodRepository = repo)
                runTest {
                    seeder.isAlreadySeeded() shouldBe false
                }
            }
        }

        test("seed() persists the full Audible mood vocabulary and they are queryable") {
            withInMemoryDatabase {
                val repo = makeMoodRepo(this)
                val seeder = MoodDomainSeeder(db = this, moodRepository = repo)
                runTest {
                    seeder.seed()
                    val moods = repo.listAll()
                    moods shouldHaveSize expectedMoodCount
                    repo.findBySlug("feel-good")?.name shouldBe "Feel-Good"
                    repo.findBySlug("thought-provoking")?.name shouldBe "Thought-Provoking"
                    repo.findBySlug("scary")?.name shouldBe "Scary"
                }
            }
        }

        test("isAlreadySeeded returns true after seed()") {
            withInMemoryDatabase {
                val repo = makeMoodRepo(this)
                val seeder = MoodDomainSeeder(db = this, moodRepository = repo)
                runTest {
                    seeder.seed()
                    seeder.isAlreadySeeded() shouldBe true
                }
            }
        }

        test("seed() is idempotent — calling twice does not throw and does not duplicate") {
            withInMemoryDatabase {
                val repo = makeMoodRepo(this)
                val seeder = MoodDomainSeeder(db = this, moodRepository = repo)
                runTest {
                    seeder.seed()
                    seeder.seed()
                    repo.listAll() shouldHaveSize expectedMoodCount
                }
            }
        }

        test("domainName and order are correct") {
            withInMemoryDatabase {
                val repo = makeMoodRepo(this)
                val seeder = MoodDomainSeeder(db = this, moodRepository = repo)
                seeder.domainName shouldBe "moods"
                seeder.order shouldBe 50
            }
        }
    })
