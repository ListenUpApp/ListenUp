@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestSeries
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Tests for [EntityRepository] — the library-shared, curated Story World entity
 * domain (characters/locations/items), dual-homed under exactly one of a series or
 * a standalone book. Every test runs against a real migrated database as a
 * SQLDelight [com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase] (`sql`),
 * with rows seeded through the SQLDelight seed helpers (see [withSqlDatabase]).
 *
 * Coverage:
 *  - round-trip of a series-homed entity and a book-homed entity;
 *  - the `updatedAt`-wins staleness guard: strictly-older writes no-op,
 *    equal-or-newer writes apply;
 *  - idempotent re-apply of the identical upsert;
 *  - soft delete tombstones the row and [SqlSyncableRepository.pullSince]
 *    minimizes its content (both home fields blanked).
 */
class EntityRepositoryTest :
    FunSpec({

        fun seriesHomedPayload(
            id: String,
            seriesId: String,
            name: String = "Vin",
            kind: EntityKind = EntityKind.CHARACTER,
            imageRef: String? = null,
            updatedAt: Long = 1_000L,
        ) = EntitySyncPayload(
            id = id,
            kind = kind,
            name = name,
            homeSeriesId = seriesId,
            homeBookId = null,
            imageRef = imageRef,
            revision = 0L,
            updatedAt = updatedAt,
            createdAt = updatedAt,
            deletedAt = null,
        )

        fun bookHomedPayload(
            id: String,
            bookId: String,
            name: String = "Vin",
            kind: EntityKind = EntityKind.CHARACTER,
            imageRef: String? = null,
            updatedAt: Long = 1_000L,
        ) = EntitySyncPayload(
            id = id,
            kind = kind,
            name = name,
            homeSeriesId = null,
            homeBookId = bookId,
            imageRef = imageRef,
            revision = 0L,
            updatedAt = updatedAt,
            createdAt = updatedAt,
            deletedAt = null,
        )

        // ── round-trip ──────────────────────────────────────────────────────────

        test("upsertEntity round-trips a series-homed entity") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                val repo = EntityRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsertEntity(
                        seriesHomedPayload(id = "vin", seriesId = "mistborn", imageRef = "img/vin.png"),
                    )

                    val stored = repo.findById("vin")
                    stored shouldNotBe null
                    stored?.name shouldBe "Vin"
                    stored?.kind shouldBe EntityKind.CHARACTER
                    stored?.homeSeriesId shouldBe "mistborn"
                    stored?.homeBookId shouldBe null
                    stored?.imageRef shouldBe "img/vin.png"
                }
            }
        }

        test("upsertEntity round-trips a book-homed entity") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("hoa1")
                val repo = EntityRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsertEntity(
                        bookHomedPayload(id = "alcatraz", bookId = "hoa1", imageRef = "img/alcatraz.png"),
                    )

                    val stored = repo.findById("alcatraz")
                    stored shouldNotBe null
                    stored?.name shouldBe "Vin"
                    stored?.homeSeriesId shouldBe null
                    stored?.homeBookId shouldBe "hoa1"
                    stored?.imageRef shouldBe "img/alcatraz.png"
                }
            }
        }

        // ── stale-write guard ──────────────────────────────────────────────────

        test("a strictly-older updatedAt write is a no-op") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                val repo = EntityRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsertEntity(seriesHomedPayload(id = "vin", seriesId = "mistborn", name = "Vin", updatedAt = 2_000L))

                    val staleResult =
                        repo.upsertEntity(
                            seriesHomedPayload(id = "vin", seriesId = "mistborn", name = "Stale Name", updatedAt = 1_000L),
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
                    repo.upsertEntity(seriesHomedPayload(id = "vin", seriesId = "mistborn", name = "Vin", updatedAt = 2_000L))
                    repo.upsertEntity(seriesHomedPayload(id = "vin", seriesId = "mistborn", name = "Renamed", updatedAt = 2_000L))

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
                    val payload = seriesHomedPayload(id = "vin", seriesId = "mistborn", updatedAt = 5_000L)

                    repo.upsertEntity(payload)
                    val second = repo.upsertEntity(payload)

                    second.shouldBeInstanceOf<AppResult.Success<EntitySyncPayload>>()
                    val stored = repo.findById("vin")
                    stored?.name shouldBe payload.name
                    stored?.kind shouldBe payload.kind
                    stored?.homeSeriesId shouldBe payload.homeSeriesId
                    stored?.homeBookId shouldBe payload.homeBookId
                    stored?.imageRef shouldBe payload.imageRef
                }
            }
        }

        // ── soft delete → tombstone ─────────────────────────────────────────────

        test("softDelete tombstones the row and pullSince delivers minimized content") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                val repo = EntityRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsertEntity(
                        seriesHomedPayload(id = "vin", seriesId = "mistborn", imageRef = "img/vin.png"),
                    )

                    repo.softDelete("vin")

                    val page = repo.pullSince(userId = null, cursor = 0L, limit = 50)
                    val tombstone = page.items.first { it.id == "vin" }
                    tombstone.deletedAt shouldNotBe null
                    tombstone.name shouldBe ""
                    tombstone.homeSeriesId shouldBe null
                    tombstone.homeBookId shouldBe null
                    tombstone.imageRef shouldBe null
                }
            }
        }
    })
