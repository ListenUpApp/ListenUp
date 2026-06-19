@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class ContributorRepositoryTest :
    FunSpec({

        test("resolveOrCreate inserts a new contributor and publishes Created") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = ContributorRepository(db = sql, bus = bus, registry = SyncRegistry())
                runTest {
                    val deferred = async { bus.subscribe().first() }
                    advanceUntilIdle()
                    val id = repo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    val busEvent = deferred.await()
                    busEvent.repo.domainName shouldBe "contributors"
                    val event = busEvent.event
                    event.shouldBeInstanceOf<SyncEvent.Created<ContributorSyncPayload>>()
                    event.id shouldBe id.value
                    (event as SyncEvent.Created<ContributorSyncPayload>).payload.name shouldBe "Brandon Sanderson"
                    event.revision shouldBe 1L
                }
            }
        }

        test("resolveOrCreate is idempotent on the normalized name — no second event") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val first = repo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    val second = repo.resolveOrCreate("  brandon   SANDERSON ", sortName = null)
                    second shouldBe first
                    repo.findById(first.value)?.revision shouldBe 1L
                }
            }
        }

        test("idAsString unwraps the value class to its raw string") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                repo.idAsStringForTest(ContributorId("c1")) shouldBe "c1"
            }
        }

        test("pullSince returns inserted contributors in revision order") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.resolveOrCreate("Author One", sortName = null)
                    repo.resolveOrCreate("Author Two", sortName = null)
                    val page = repo.pullSince(userId = null, cursor = 0L, limit = 10)
                    page.items.map { it.name } shouldBe listOf("Author One", "Author Two")
                }
            }
        }

        test("display-order variants of the same name resolve to one contributor") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val id1 = repo.resolveOrCreate("Brandon Sanderson", sortName = "Sanderson, Brandon")
                    val id2 = repo.resolveOrCreate("Sanderson, Brandon", sortName = "Sanderson, Brandon")
                    id2 shouldBe id1
                }
            }
        }

        test("first display name wins; later variant does not overwrite it") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val id = repo.resolveOrCreate("Brandon Sanderson", sortName = "Sanderson, Brandon")
                    repo.resolveOrCreate("Sanderson, Brandon", sortName = "Sanderson, Brandon")
                    repo.findById(id.value)?.name shouldBe "Brandon Sanderson"
                }
            }
        }

        test("null sortName derives the sort form as the key — same person found on second call") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val a = repo.resolveOrCreate("Unique Person", sortName = null)
                    val b = repo.resolveOrCreate("Unique Person", sortName = null)
                    b shouldBe a
                }
            }
        }

        test("manual (null sortName) and scanner (derived sortName) paths converge to one row") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    // Simulates BookServiceImpl / BookMetadataApplier (null sortName — repo derives)
                    val manual = repo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    // Simulates the scanner (explicit derived sort name)
                    val scanner = repo.resolveOrCreate("Brandon Sanderson", sortName = "Sanderson, Brandon")
                    scanner shouldBe manual
                }
            }
        }

        // ── SQLDelight cutover gap tests ──────────────────────────────────────────

        test("findById returns enrichment columns round-tripped through upsert") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(
                        ContributorSyncPayload(
                            id = "enriched",
                            name = "Enriched Author",
                            sortName = "Author, Enriched",
                            revision = 0L,
                            updatedAt = 0L,
                            createdAt = 0L,
                            deletedAt = null,
                            asin = "B0ASIN",
                            description = "A described author.",
                            imagePath = "images/enriched.jpg",
                            imageBlurHash = "LKO2",
                            birthDate = "1975-12-19",
                            deathDate = null,
                            website = "https://example.com",
                        ),
                    )
                    val read = repo.findById("enriched")
                    read.shouldNotBeNull()
                    read.asin shouldBe "B0ASIN"
                    read.description shouldBe "A described author."
                    read.imagePath shouldBe "images/enriched.jpg"
                    read.imageBlurHash shouldBe "LKO2"
                    read.birthDate shouldBe "1975-12-19"
                    read.deathDate.shouldBeNull()
                    read.website shouldBe "https://example.com"
                }
            }
        }

        test("readPayloads (batched selectByIds) returns rows and aliases in requested order") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(contributorFixture("c-a", "Author A", aliases = listOf("Pen A")))
                    repo.upsert(contributorFixture("c-b", "Author B"))
                    repo.upsert(contributorFixture("c-c", "Author C", aliases = listOf("Pen C1", "Pen C2")))

                    // pullSince drives readPayloads; assert the batched read hydrates aliases.
                    val page = repo.pullSince(userId = null, cursor = 0L, limit = 10)
                    page.items.map { it.id } shouldContainExactly listOf("c-a", "c-b", "c-c")
                    page.items.first { it.id == "c-c" }.aliases shouldContainExactly listOf("Pen C1", "Pen C2")
                    page.items.first { it.id == "c-b" }.aliases shouldBe emptyList()
                }
            }
        }

        test("findById by normalized-name dedup: variant casing resolves to the same row") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val first = repo.resolveOrCreate("Ursula K. Le Guin", sortName = null)
                    val again = repo.resolveOrCreate("  ursula k. LE GUIN  ", sortName = null)
                    again shouldBe first
                    // Only one row exists — listLiveIds has exactly the one id.
                    repo.listLiveIds() shouldBe setOf(first.value)
                }
            }
        }

        test("soft-delete round-trip: tombstones the row and excludes it from listLiveIds") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val id = repo.resolveOrCreate("Doomed Author", sortName = null)
                    val result = repo.softDelete(id)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // Tombstoned: findById still reads the row (catch-up needs tombstones),
                    // but listLiveIds excludes it.
                    repo.findById(id.value).shouldNotBeNull()
                    repo.findById(id.value)?.deletedAt.shouldNotBeNull()
                    repo.listLiveIds() shouldBe emptySet()
                }
            }
        }

        test("digest reflects inserted contributors and is stable") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.resolveOrCreate("Author One", sortName = null)
                    repo.resolveOrCreate("Author Two", sortName = null)
                    val cursor = repo.pullSince(userId = null, cursor = 0L, limit = 10).nextCursor!!
                    val digest = repo.digest(userId = null, cursor = cursor)
                    digest.count shouldBe 2
                    digest.hash.startsWith("sha256:") shouldBe true
                }
            }
        }
    })

private fun contributorFixture(
    id: String,
    name: String,
    sortName: String? = null,
    aliases: List<String> = emptyList(),
): ContributorSyncPayload =
    ContributorSyncPayload(
        id = id,
        name = name,
        sortName = sortName,
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
        aliases = aliases,
    )
