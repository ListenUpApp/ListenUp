@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.seed

import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Smoke tests for [GenreDomainSeeder]. Verifies the recursive walk produces a
 * non-empty tree with parent linkage and depth correctness, and that the
 * idempotency gate ([DomainSeeder.isAlreadySeeded]) prevents re-seeding.
 */
class GenreDomainSeederTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_730_000_000_000L))

        test("isAlreadySeeded returns false on fresh database") {
            withInMemoryDatabase {
                runTest {
                    val seeder =
                        GenreDomainSeeder(
                            genreRepository =
                                GenreRepository(
                                    db = this@withInMemoryDatabase.asSqlDatabase(),
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
            withInMemoryDatabase {
                val db = this
                runTest {
                    val seeder =
                        GenreDomainSeeder(
                            genreRepository =
                                GenreRepository(db.asSqlDatabase(), ChangeBus(), SyncRegistry(), fixedClock),
                            clock = fixedClock,
                        )
                    seeder.seed()

                    transaction(db) {
                        val live = GenreTable.selectAll().where { GenreTable.deletedAt.isNull() }.toList()
                        live.size shouldBeGreaterThan 50 // ~70 nodes total in the default tree

                        // Verify a sample root.
                        val fiction =
                            live.first { it[GenreTable.slug] == "fiction" }
                        fiction[GenreTable.path] shouldBe "/fiction"
                        fiction[GenreTable.parentId] shouldBe null
                        fiction[GenreTable.depth] shouldBe 0

                        // Verify a sample depth-2 leaf.
                        val epic = live.first { it[GenreTable.slug] == "epic-fantasy" }
                        epic[GenreTable.path] shouldBe "/fiction/fantasy/epic-fantasy"
                        epic[GenreTable.depth] shouldBe 2

                        // The depth-3 path stays under the fiction subtree.
                        live.map { it[GenreTable.slug] } shouldContain "ya-fantasy"
                    }
                }
            }
        }

        test("isAlreadySeeded returns true after seeding") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val seeder =
                        GenreDomainSeeder(
                            genreRepository =
                                GenreRepository(db.asSqlDatabase(), ChangeBus(), SyncRegistry(), fixedClock),
                            clock = fixedClock,
                        )
                    seeder.seed()
                    seeder.isAlreadySeeded() shouldBe true
                }
            }
        }

        test("running seed() twice is idempotent (no duplicate slugs)") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val seeder =
                        GenreDomainSeeder(
                            genreRepository =
                                GenreRepository(db.asSqlDatabase(), ChangeBus(), SyncRegistry(), fixedClock),
                            clock = fixedClock,
                        )
                    seeder.seed()
                    val afterFirst =
                        transaction(db) {
                            GenreTable.selectAll().where { GenreTable.deletedAt.isNull() }.count()
                        }
                    seeder.seed()
                    val afterSecond =
                        transaction(db) {
                            GenreTable.selectAll().where { GenreTable.deletedAt.isNull() }.count()
                        }
                    afterSecond shouldBe afterFirst
                }
            }
        }

        test("Fantasy subtree has all 10 spec-listed children") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val seeder =
                        GenreDomainSeeder(
                            genreRepository =
                                GenreRepository(db.asSqlDatabase(), ChangeBus(), SyncRegistry(), fixedClock),
                            clock = fixedClock,
                        )
                    seeder.seed()

                    transaction(db) {
                        val fantasyId =
                            GenreTable
                                .selectAll()
                                .where { (GenreTable.slug eq "fantasy") and GenreTable.deletedAt.isNull() }
                                .single()[GenreTable.id]
                        val children =
                            GenreTable
                                .selectAll()
                                .where { GenreTable.parentId eq fantasyId }
                                .map { it[GenreTable.slug] }
                                .toSet()
                        // From defaults.go — 10 sub-genres under Fantasy.
                        children.size shouldBe 10
                        children shouldContain "epic-fantasy"
                        children shouldContain "urban-fantasy"
                        children shouldContain "litrpg"
                    }
                }
            }
        }
    })
