@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.SeriesId
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

class SeriesRepositoryTest :
    FunSpec({

        test("resolveOrCreate inserts a new series and publishes Created") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = SeriesRepository(db = sql, bus = bus, registry = SyncRegistry())
                runTest {
                    val deferred = async { bus.subscribe().first() }
                    advanceUntilIdle()
                    val id = repo.resolveOrCreate("The Stormlight Archive")
                    val busEvent = deferred.await()
                    busEvent.repo.domainName shouldBe "series"
                    val event = busEvent.event
                    event.shouldBeInstanceOf<SyncEvent.Created<SeriesSyncPayload>>()
                    event.id shouldBe id.value
                    (event as SyncEvent.Created<SeriesSyncPayload>).payload.name shouldBe "The Stormlight Archive"
                }
            }
        }

        test("resolveOrCreate is idempotent on the normalized name") {
            withSqlDatabase {
                val repo = SeriesRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val first = repo.resolveOrCreate("The Stormlight Archive")
                    val second = repo.resolveOrCreate("  the   STORMLIGHT archive ")
                    second shouldBe first
                }
            }
        }

        test("idAsString unwraps the value class to its raw string") {
            withSqlDatabase {
                val repo = SeriesRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                repo.idAsStringForTest(SeriesId("s1")) shouldBe "s1"
            }
        }

        // ── SQLDelight cutover gap tests ──────────────────────────────────────────

        test("first display name wins; later variant does not overwrite stored casing") {
            withSqlDatabase {
                val repo = SeriesRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val id = repo.resolveOrCreate("Mistborn")
                    repo.resolveOrCreate("MISTBORN")
                    repo.findById(id.value)?.name shouldBe "Mistborn"
                }
            }
        }

        test("findById returns enrichment columns round-tripped through upsert") {
            withSqlDatabase {
                val repo = SeriesRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(
                        SeriesSyncPayload(
                            id = "enriched",
                            name = "Enriched Series",
                            sortName = "Enriched Series",
                            revision = 0L,
                            updatedAt = 0L,
                            createdAt = 0L,
                            deletedAt = null,
                            asin = "B0SERIES",
                            description = "A described series.",
                            coverPath = "covers/enriched.jpg",
                            coverBlurHash = "LKO2",
                        ),
                    )
                    val read = repo.findById("enriched")
                    read.shouldNotBeNull()
                    read.asin shouldBe "B0SERIES"
                    read.description shouldBe "A described series."
                    read.coverPath shouldBe "covers/enriched.jpg"
                    read.coverBlurHash shouldBe "LKO2"
                    read.sortName shouldBe "Enriched Series"
                }
            }
        }

        test("pullSince (batched readPayloads) returns inserted series in revision order") {
            withSqlDatabase {
                val repo = SeriesRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.resolveOrCreate("Series One")
                    repo.resolveOrCreate("Series Two")
                    val page = repo.pullSince(userId = null, cursor = 0L, limit = 10)
                    page.items.map { it.name } shouldContainExactly listOf("Series One", "Series Two")
                }
            }
        }

        test("soft-delete round-trip: tombstones the row and excludes it from listLiveIds") {
            withSqlDatabase {
                val repo = SeriesRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val id = repo.resolveOrCreate("Doomed Series")
                    val result = repo.softDelete(id)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    repo.findById(id.value).shouldNotBeNull()
                    repo.findById(id.value)?.deletedAt.shouldNotBeNull()
                    repo.listLiveIds() shouldBe emptySet()
                }
            }
        }

        test("digest reflects inserted series and is stable") {
            withSqlDatabase {
                val repo = SeriesRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.resolveOrCreate("Series One")
                    repo.resolveOrCreate("Series Two")
                    val cursor = repo.pullSince(userId = null, cursor = 0L, limit = 10).nextCursor!!
                    val digest = repo.digest(userId = null, cursor = cursor)
                    digest.count shouldBe 2
                    digest.hash.startsWith("sha256:") shouldBe true
                }
            }
        }

        test("allIdRevisionsForTest returns only live rows (tombstones excluded), matching the digest") {
            withSqlDatabase {
                val repo = SeriesRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val keep = repo.resolveOrCreate("Kept Series")
                    val gone = repo.resolveOrCreate("Gone Series")
                    repo.softDelete(gone)
                    // The digest counts LIVE rows only (F1); the parity helper mirrors that set, so a
                    // tombstoned series is excluded — feeding the client DigestComputer the same rows.
                    val ids = repo.allIdRevisionsForTest().map { it.first }
                    ids shouldContainExactly listOf(keep.value)
                }
            }
        }

        test("sortName null is preserved on resolveOrCreate insert") {
            withSqlDatabase {
                val repo = SeriesRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val id = repo.resolveOrCreate("No Sort Series")
                    repo.findById(id.value)?.sortName.shouldBeNull()
                }
            }
        }

        // ── resolveOrCreateAll (batch) ────────────────────────────────────────────

        test("resolveOrCreateAll resolves pre-existing and brand-new names to correct ids") {
            withSqlDatabase {
                val repo = SeriesRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val existingId = repo.resolveOrCreate("The Stormlight Archive")

                    val map = repo.resolveOrCreateAll(listOf("The Stormlight Archive", "Mistborn"))

                    map[normalizeForDedup("The Stormlight Archive")] shouldBe existingId
                    val newKey = normalizeForDedup("Mistborn")
                    map[newKey].shouldNotBeNull()
                    map[newKey] shouldBe repo.resolveOrCreate("Mistborn")
                }
            }
        }

        test("resolveOrCreateAll dedups case/whitespace exactly like resolveOrCreate") {
            withSqlDatabase {
                val repo = SeriesRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val map =
                        repo.resolveOrCreateAll(
                            listOf("The Stormlight Archive", "  the   STORMLIGHT archive "),
                        )
                    map.values.toSet().size shouldBe 1
                    map.values.first() shouldBe repo.resolveOrCreate("The Stormlight Archive")
                    repo.listLiveIds().size shouldBe 1
                }
            }
        }

        test("resolveOrCreateAll emits one Created per genuinely-new series") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = SeriesRepository(db = sql, bus = bus, registry = SyncRegistry())
                runTest {
                    repo.resolveOrCreate("Pre Existing Series")

                    val events = mutableListOf<SyncEvent<SeriesSyncPayload>>()
                    val collector =
                        async {
                            @Suppress("UNCHECKED_CAST")
                            bus.subscribe().collect { events += it.event as SyncEvent<SeriesSyncPayload> }
                        }
                    advanceUntilIdle()

                    repo.resolveOrCreateAll(listOf("Pre Existing Series", "New Series A", "New Series B"))
                    advanceUntilIdle()
                    collector.cancel()

                    // The bus replays the original "Pre Existing Series" Created; the batch must NOT
                    // re-emit it (exactly one) while emitting one Created per genuinely-new series.
                    val createdNames = events.filterIsInstance<SyncEvent.Created<SeriesSyncPayload>>().map { it.payload.name }
                    createdNames.toSet() shouldBe setOf("Pre Existing Series", "New Series A", "New Series B")
                    createdNames.count { it == "Pre Existing Series" } shouldBe 1
                    createdNames.count { it == "New Series A" } shouldBe 1
                    createdNames.count { it == "New Series B" } shouldBe 1
                }
            }
        }

        test("resolveOrCreateAll on empty input is a no-op returning an empty map") {
            withSqlDatabase {
                val repo = SeriesRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.resolveOrCreateAll(emptyList()) shouldBe emptyMap()
                    repo.listLiveIds() shouldBe emptySet()
                }
            }
        }
    })
