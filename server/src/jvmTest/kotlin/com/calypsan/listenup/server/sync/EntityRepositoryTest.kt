@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BioEntryMode
import com.calypsan.listenup.api.sync.BioEntryPayload
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestSeries
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Tests for [EntityRepository] — the library-shared, curated Story World entity
 * domain (characters/locations/items). Every test runs against a real migrated
 * database as a SQLDelight [com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase]
 * (`sql`), with rows seeded through the SQLDelight seed helpers (see
 * [withSqlDatabase]).
 *
 * Coverage:
 *  - round-trip of an entity with its bio-entry child set (including a
 *    non-anchored and an anchored entry);
 *  - the child-set-replace pattern: a second [EntityRepository.upsertEntity]
 *    fully replaces the bio-entry set, with no orphaned rows left behind;
 *  - the `updatedAt`-wins staleness guard: strictly-older writes no-op,
 *    equal-or-newer writes apply;
 *  - soft delete tombstones the row and [SqlSyncableRepository.pullSince]
 *    minimizes its content;
 *  - the `entity_bio_entries` FK `ON DELETE CASCADE` safety net beneath the
 *    app-layer soft-delete (entities are never hard-deleted by the service, but
 *    the constraint itself must hold).
 */
class EntityRepositoryTest :
    FunSpec({

        fun entityPayload(
            id: String,
            seriesId: String,
            name: String = "Vin",
            kind: EntityKind = EntityKind.CHARACTER,
            imageRef: String? = null,
            bioEntries: List<BioEntryPayload> = emptyList(),
            updatedAt: Long = 1_000L,
        ) = EntitySyncPayload(
            id = id,
            kind = kind,
            name = name,
            homeSeriesId = seriesId,
            imageRef = imageRef,
            bioEntries = bioEntries,
            revision = 0L,
            updatedAt = updatedAt,
            createdAt = updatedAt,
            deletedAt = null,
        )

        // ── round-trip ──────────────────────────────────────────────────────────

        test("upsertEntity round-trips an entity with its bio-entry child set") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("hoa1")
                val repo = EntityRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    val entries =
                        listOf(
                            BioEntryPayload(
                                id = "bio1",
                                bookId = null,
                                positionMs = null,
                                mode = BioEntryMode.APPEND,
                                text = "A street urchin with a strange talent.",
                                sortKey = 0,
                            ),
                            BioEntryPayload(
                                id = "bio2",
                                bookId = "hoa1",
                                positionMs = 120_000L,
                                mode = BioEntryMode.REPLACE,
                                text = "Revealed as the Heir of the Survivor.",
                                sortKey = 1,
                            ),
                        )
                    repo.upsertEntity(
                        entityPayload(id = "vin", seriesId = "mistborn", imageRef = "img/vin.png", bioEntries = entries),
                    )

                    val stored = repo.findById("vin")
                    stored shouldNotBe null
                    stored?.name shouldBe "Vin"
                    stored?.kind shouldBe EntityKind.CHARACTER
                    stored?.homeSeriesId shouldBe "mistborn"
                    stored?.imageRef shouldBe "img/vin.png"
                    stored?.bioEntries shouldContainExactly entries
                }
            }
        }

        // ── child-set replace ──────────────────────────────────────────────────

        test("a second upsertEntity fully replaces the bio-entry set, leaving no orphans") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                val repo = EntityRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    val firstEntries =
                        listOf(
                            BioEntryPayload(id = "a", mode = BioEntryMode.APPEND, text = "one", sortKey = 0),
                            BioEntryPayload(id = "b", mode = BioEntryMode.APPEND, text = "two", sortKey = 1),
                            BioEntryPayload(id = "c", mode = BioEntryMode.APPEND, text = "three", sortKey = 2),
                        )
                    repo.upsertEntity(
                        entityPayload(id = "vin", seriesId = "mistborn", bioEntries = firstEntries, updatedAt = 1_000L),
                    )

                    val secondEntries =
                        listOf(BioEntryPayload(id = "d", mode = BioEntryMode.APPEND, text = "only one now", sortKey = 0))
                    repo.upsertEntity(
                        entityPayload(id = "vin", seriesId = "mistborn", bioEntries = secondEntries, updatedAt = 2_000L),
                    )

                    val stored = repo.findById("vin")
                    stored?.bioEntries shouldContainExactly secondEntries

                    // No orphaned rows survive under the raw table either.
                    sql.entityBioEntriesQueries.selectForEntity("vin").executeAsList() shouldHaveSize 1
                }
            }
        }

        // ── stale-write guard ──────────────────────────────────────────────────

        test("a strictly-older updatedAt write is a no-op") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                val repo = EntityRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsertEntity(entityPayload(id = "vin", seriesId = "mistborn", name = "Vin", updatedAt = 2_000L))

                    val staleResult =
                        repo.upsertEntity(
                            entityPayload(id = "vin", seriesId = "mistborn", name = "Stale Name", updatedAt = 1_000L),
                        )

                    staleResult.shouldBeInstanceOf<AppResult.Success<EntitySyncPayload>>()
                    val stored = repo.findById("vin")
                    stored?.name shouldBe "Vin"
                }
            }
        }

        test("an equal updatedAt write is NOT treated as stale — it still applies") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                val repo = EntityRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsertEntity(entityPayload(id = "vin", seriesId = "mistborn", name = "Vin", updatedAt = 2_000L))
                    repo.upsertEntity(entityPayload(id = "vin", seriesId = "mistborn", name = "Renamed", updatedAt = 2_000L))

                    repo.findById("vin")?.name shouldBe "Renamed"
                }
            }
        }

        // ── idempotency ─────────────────────────────────────────────────────────

        test("re-applying the identical upsert twice converges to the same stored content") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                val repo = EntityRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    val entries = listOf(BioEntryPayload(id = "a", mode = BioEntryMode.APPEND, text = "one", sortKey = 0))
                    val payload = entityPayload(id = "vin", seriesId = "mistborn", bioEntries = entries, updatedAt = 5_000L)

                    repo.upsertEntity(payload)
                    val second = repo.upsertEntity(payload)

                    second.shouldBeInstanceOf<AppResult.Success<EntitySyncPayload>>()
                    val stored = repo.findById("vin")
                    stored?.name shouldBe payload.name
                    stored?.kind shouldBe payload.kind
                    stored?.homeSeriesId shouldBe payload.homeSeriesId
                    stored?.imageRef shouldBe payload.imageRef
                    stored?.bioEntries shouldContainExactly entries
                }
            }
        }

        // ── soft delete → tombstone ─────────────────────────────────────────────

        test("softDelete tombstones the row and pullSince delivers minimized content") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                val repo = EntityRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    val entries = listOf(BioEntryPayload(id = "a", mode = BioEntryMode.APPEND, text = "secret", sortKey = 0))
                    repo.upsertEntity(
                        entityPayload(id = "vin", seriesId = "mistborn", imageRef = "img/vin.png", bioEntries = entries),
                    )

                    repo.softDelete("vin")

                    val page = repo.pullSince(userId = null, cursor = 0L, limit = 50)
                    val tombstone = page.items.first { it.id == "vin" }
                    tombstone.deletedAt shouldNotBe null
                    tombstone.name shouldBe ""
                    tombstone.homeSeriesId shouldBe ""
                    tombstone.imageRef shouldBe null
                    tombstone.bioEntries.shouldBeEmpty()
                }
            }
        }

        // ── FK cascade safety net ───────────────────────────────────────────────

        test("hard-deleting an entity row cascades to its bio-entry rows (ON DELETE CASCADE)") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                val repo = EntityRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    val entries = listOf(BioEntryPayload(id = "a", mode = BioEntryMode.APPEND, text = "one", sortKey = 0))
                    repo.upsertEntity(entityPayload(id = "vin", seriesId = "mistborn", bioEntries = entries))
                    sql.entityBioEntriesQueries.selectForEntity("vin").executeAsList() shouldHaveSize 1

                    // The hard-delete safety net beneath the app-layer soft-delete cascade:
                    // PRAGMA foreign_keys=ON makes the FK ON DELETE CASCADE remove the bio entries.
                    // Re-assert the pragma on this connection immediately before the delete — it is
                    // connection-scoped and must be active when the cascade fires.
                    driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
                    sql.transaction {
                        sql.entitiesQueries.deleteById("vin")
                    }

                    sql.entityBioEntriesQueries
                        .selectForEntity("vin")
                        .executeAsList()
                        .shouldBeEmpty()
                }
            }
        }
    })
