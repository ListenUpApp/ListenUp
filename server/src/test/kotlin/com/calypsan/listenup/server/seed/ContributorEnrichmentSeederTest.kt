package com.calypsan.listenup.server.seed

import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

class ContributorEnrichmentSeederTest :
    FunSpec({

        fun makeRepo(db: Database): ContributorRepository = ContributorRepository(db, ChangeBus(), SyncRegistry())

        test("seed enriches contributors that have no description") {
            withInMemoryDatabase {
                val db = this
                val repo = makeRepo(db)
                runTest {
                    // Resolve "Wren Halloway" and "Marlowe Finch" — two of the demo contributors
                    repo.resolveOrCreate("Wren Halloway")
                    repo.resolveOrCreate("Marlowe Finch")

                    val seeder = ContributorEnrichmentSeeder(db, repo)
                    seeder.isAlreadySeeded() shouldBe false
                    seeder.seed()

                    // Both should now have a non-null description
                    val wren = repo.findByName("Wren Halloway")
                    wren.shouldNotBeNull()
                    wren.description.shouldNotBeNull()
                    wren.sortName shouldBe "Halloway, Wren"

                    val marlowe = repo.findByName("Marlowe Finch")
                    marlowe.shouldNotBeNull()
                    marlowe.description.shouldNotBeNull()
                    marlowe.sortName shouldBe "Finch, Marlowe"
                }
            }
        }

        test("seed skips contributors that already have a description") {
            withInMemoryDatabase {
                val db = this
                val repo = makeRepo(db)
                runTest {
                    // Pre-populate with a description already set
                    val id = repo.resolveOrCreate("Wren Halloway")
                    val existing = repo.findById(id.value)!!
                    repo.upsert(existing.copy(description = "Pre-existing bio."), clientOpId = null)

                    val seeder = ContributorEnrichmentSeeder(db, repo)
                    // isAlreadySeeded should return true because at least one row has a description
                    seeder.isAlreadySeeded() shouldBe true

                    seeder.seed()

                    // Description must be the original, not overwritten
                    val after = repo.findByName("Wren Halloway")
                    after?.description shouldBe "Pre-existing bio."
                }
            }
        }

        test("seed on a contributor not in DB logs a skip — no error") {
            withInMemoryDatabase {
                val db = this
                val repo = makeRepo(db)
                runTest {
                    // No contributors at all — all ENRICHMENTS will be deferred
                    val seeder = ContributorEnrichmentSeeder(db, repo)
                    seeder.isAlreadySeeded() shouldBe false
                    // Must not throw
                    seeder.seed()
                    seeder.isAlreadySeeded() shouldBe false
                }
            }
        }

        test("isAlreadySeeded returns false when all contributors have null description") {
            withInMemoryDatabase {
                val db = this
                val repo = makeRepo(db)
                runTest {
                    repo.resolveOrCreate("Unknown Author")
                    // description is null by default from resolveOrCreate
                    val seeder = ContributorEnrichmentSeeder(db, repo)
                    seeder.isAlreadySeeded() shouldBe false
                }
            }
        }
    })

/**
 * Resolves the contributor by [displayName] — returns the existing row if one
 * exists, or creates a new (unenriched) one. The returned payload reflects the
 * current DB state, including any enrichment applied by the seeder.
 */
private suspend fun ContributorRepository.findByName(displayName: String) = findById(resolveOrCreate(displayName).value)
