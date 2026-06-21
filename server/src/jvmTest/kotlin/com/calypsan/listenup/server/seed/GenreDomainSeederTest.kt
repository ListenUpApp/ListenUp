@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.seed

import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Smoke tests for [GenreDomainSeeder]. Verifies the recursive walk produces a
 * non-empty tree with parent linkage and depth correctness, and that the
 * idempotency gate ([DomainSeeder.isAlreadySeeded]) prevents re-seeding.
 */
class GenreDomainSeederTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_730_000_000_000L))

        test("isAlreadySeeded returns false on fresh database") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val seeder =
                        GenreDomainSeeder(
                            genreRepository =
                                GenreRepository(
                                    db = dbs.sql,
                                    bus = ChangeBus(),
                                    registry = SyncRegistry(),
                                    clock = fixedClock,
                                ),
                            clock = fixedClock,
                        )
                    seeder.isAlreadySeeded() shouldBe false
                }
            }
        }

        test("seed() creates the full default tree with correct depth + path") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val seeder =
                        GenreDomainSeeder(
                            genreRepository =
                                GenreRepository(dbs.sql, ChangeBus(), SyncRegistry(), fixedClock),
                            clock = fixedClock,
                        )
                    seeder.seed()

                    val live =
                        dbs.sql.genresQueries
                            .listLiveOrderedByPath()
                            .executeAsList()
                    live.size shouldBeGreaterThan 50 // ~70 nodes total in the default tree

                    // Verify a sample root.
                    val fiction = live.first { it.slug == "fiction" }
                    fiction.path shouldBe "/fiction"
                    fiction.parent_id shouldBe null
                    fiction.depth shouldBe 0L

                    // Verify a sample depth-2 leaf.
                    val epic = live.first { it.slug == "epic-fantasy" }
                    epic.path shouldBe "/fiction/fantasy/epic-fantasy"
                    epic.depth shouldBe 2L

                    // The depth-3 path stays under the fiction subtree.
                    live.map { it.slug } shouldContain "ya-fantasy"
                }
            }
        }

        test("isAlreadySeeded returns true after seeding") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val seeder =
                        GenreDomainSeeder(
                            genreRepository =
                                GenreRepository(dbs.sql, ChangeBus(), SyncRegistry(), fixedClock),
                            clock = fixedClock,
                        )
                    seeder.seed()
                    seeder.isAlreadySeeded() shouldBe true
                }
            }
        }

        test("running seed() twice is idempotent (no duplicate slugs)") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val seeder =
                        GenreDomainSeeder(
                            genreRepository =
                                GenreRepository(dbs.sql, ChangeBus(), SyncRegistry(), fixedClock),
                            clock = fixedClock,
                        )
                    seeder.seed()
                    val afterFirst =
                        dbs.sql.genresQueries
                            .countLive()
                            .executeAsOne()
                    seeder.seed()
                    val afterSecond =
                        dbs.sql.genresQueries
                            .countLive()
                            .executeAsOne()
                    afterSecond shouldBe afterFirst
                }
            }
        }

        test("Fantasy subtree has all 10 spec-listed children") {
            withSqlDatabase {
                val dbs = this
                runTest {
                    val seeder =
                        GenreDomainSeeder(
                            genreRepository =
                                GenreRepository(dbs.sql, ChangeBus(), SyncRegistry(), fixedClock),
                            clock = fixedClock,
                        )
                    seeder.seed()

                    val fantasyId =
                        dbs.sql.genresQueries
                            .findBySlug("fantasy")
                            .executeAsOneOrNull()
                            ?: error("fantasy genre not found")
                    val children =
                        dbs.sql.genresQueries
                            .directChildren(parent_id = fantasyId)
                            .executeAsList()
                            .toSet()
                    // directChildren returns ids; fetch slugs by joining through listLiveOrderedByPath.
                    val childSlugs =
                        dbs.sql.genresQueries
                            .listLiveOrderedByPath()
                            .executeAsList()
                            .filter { it.id in children }
                            .map { it.slug }
                            .toSet()
                    // From defaults.go — 10 sub-genres under Fantasy.
                    childSlugs.size shouldBe 10
                    childSlugs shouldContain "epic-fantasy"
                    childSlugs shouldContain "urban-fantasy"
                    childSlugs shouldContain "litrpg"
                }
            }
        }
    })
