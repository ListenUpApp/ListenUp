package com.calypsan.listenup.server.seed

import com.calypsan.listenup.server.testing.asSqlDatabase

import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Smoke tests for [TagDomainSeeder].
 *
 * Uses the real [TagRepository] against an in-memory SQLite DB (via Flyway) so
 * the [isAlreadySeeded] check and the seeded rows exercise the real schema and
 * write-path.
 */
class TagDomainSeederTest :
    FunSpec({

        fun makeTagRepo(db: Database): TagRepository = TagRepository(db.asSqlDatabase(), ChangeBus(), SyncRegistry())

        test("isAlreadySeeded returns false when no tags exist") {
            withInMemoryDatabase {
                val repo = makeTagRepo(this)
                val seeder = TagDomainSeeder(sql = this.asSqlDatabase(), tagRepository = repo)
                runTest {
                    seeder.isAlreadySeeded() shouldBe false
                }
            }
        }

        test("seedDefault() persists 4 tags and they are queryable via TagRepository") {
            withInMemoryDatabase {
                val repo = makeTagRepo(this)
                val seeder = TagDomainSeeder(sql = this.asSqlDatabase(), tagRepository = repo)
                runTest {
                    seeder.seed()
                    val tags = repo.listAll()
                    tags shouldHaveSize 4
                    tags.map { it.slug }.toSet() shouldBe
                        setOf("science-fiction", "fantasy", "mystery", "non-fiction")
                }
            }
        }

        test("isAlreadySeeded returns true after seed()") {
            withInMemoryDatabase {
                val repo = makeTagRepo(this)
                val seeder = TagDomainSeeder(sql = this.asSqlDatabase(), tagRepository = repo)
                runTest {
                    seeder.seed()
                    seeder.isAlreadySeeded() shouldBe true
                }
            }
        }

        test("seed() is idempotent — calling twice does not throw") {
            withInMemoryDatabase {
                val repo = makeTagRepo(this)
                val seeder = TagDomainSeeder(sql = this.asSqlDatabase(), tagRepository = repo)
                runTest {
                    seeder.seed()
                    seeder.seed() // must not throw regardless of duplicate slug behaviour
                }
            }
        }

        test("domainName and order are correct") {
            withInMemoryDatabase {
                val repo = makeTagRepo(this)
                val seeder = TagDomainSeeder(sql = this.asSqlDatabase(), tagRepository = repo)
                seeder.domainName shouldBe "tags"
                seeder.order shouldBe 30
            }
        }

        test("seeded tags have non-null slugs matching the expected canonical values") {
            withInMemoryDatabase {
                val repo = makeTagRepo(this)
                val seeder = TagDomainSeeder(sql = this.asSqlDatabase(), tagRepository = repo)
                runTest {
                    seeder.seed()

                    val sciFi = repo.findBySlug("science-fiction")
                    sciFi?.name shouldBe "Science Fiction"
                    sciFi?.slug shouldBe "science-fiction"

                    val fantasy = repo.findBySlug("fantasy")
                    fantasy?.name shouldBe "Fantasy"

                    val mystery = repo.findBySlug("mystery")
                    mystery?.name shouldBe "Mystery"

                    val nonFiction = repo.findBySlug("non-fiction")
                    nonFiction?.name shouldBe "Non-Fiction"
                }
            }
        }
    })
