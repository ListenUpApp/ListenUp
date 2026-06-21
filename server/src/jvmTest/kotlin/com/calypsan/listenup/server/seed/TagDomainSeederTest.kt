package com.calypsan.listenup.server.seed

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Smoke tests for [TagDomainSeeder].
 *
 * Uses the real [TagRepository] against an in-memory SQLite DB (via Flyway) so
 * the [isAlreadySeeded] check and the seeded rows exercise the real schema and
 * write-path.
 */
class TagDomainSeederTest :
    FunSpec({

        fun makeTagRepo(sql: ListenUpDatabase): TagRepository = TagRepository(sql, ChangeBus(), SyncRegistry())

        test("isAlreadySeeded returns false when no tags exist") {
            withSqlDatabase {
                val repo = makeTagRepo(sql)
                val seeder = TagDomainSeeder(sql = sql, tagRepository = repo)
                runTest {
                    seeder.isAlreadySeeded() shouldBe false
                }
            }
        }

        test("seedDefault() persists 4 tags and they are queryable via TagRepository") {
            withSqlDatabase {
                val repo = makeTagRepo(sql)
                val seeder = TagDomainSeeder(sql = sql, tagRepository = repo)
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
            withSqlDatabase {
                val repo = makeTagRepo(sql)
                val seeder = TagDomainSeeder(sql = sql, tagRepository = repo)
                runTest {
                    seeder.seed()
                    seeder.isAlreadySeeded() shouldBe true
                }
            }
        }

        test("seed() is idempotent — calling twice does not throw") {
            withSqlDatabase {
                val repo = makeTagRepo(sql)
                val seeder = TagDomainSeeder(sql = sql, tagRepository = repo)
                runTest {
                    seeder.seed()
                    seeder.seed() // must not throw regardless of duplicate slug behaviour
                }
            }
        }

        test("domainName and order are correct") {
            withSqlDatabase {
                val repo = makeTagRepo(sql)
                val seeder = TagDomainSeeder(sql = sql, tagRepository = repo)
                seeder.domainName shouldBe "tags"
                seeder.order shouldBe 30
            }
        }

        test("seeded tags have non-null slugs matching the expected canonical values") {
            withSqlDatabase {
                val repo = makeTagRepo(sql)
                val seeder = TagDomainSeeder(sql = sql, tagRepository = repo)
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
