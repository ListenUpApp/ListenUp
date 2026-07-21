package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Unit coverage for [staleCursorFloor] — the pure predicate both [SyncStreamServiceImpl]'s
 * pre-subscribe fast path and its attach-time re-check share (SERVER-SYNC-02). Isolating
 * it from the stream session lets the staleness math itself be pinned deterministically: the actual
 * race the attach-time re-check closes (a burst landing between the pre-check snapshot and the
 * moment the [ChangeBus] subscription attaches) is not reliably reproducible through
 * `testApplication`'s real dispatcher, so this is the level that check's correctness is proven at.
 */
class StaleCursorFloorTest :
    FunSpec({

        test("returns null when the buffer is empty regardless of lastEventId") {
            val bus = ChangeBus()
            staleCursorFloor(bus, lastEventId = 5L).shouldBeNull()
        }

        test("returns null when lastEventId is null (fresh subscriber)") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                runTest {
                    repo.upsert(Tag(id = "a", name = "n", slug = "n", revision = 0, updatedAt = 0))
                    staleCursorFloor(bus, lastEventId = null).shouldBeNull()
                }
            }
        }

        test("returns null when lastEventId is at or above the current floor") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                runTest {
                    repo.upsert(Tag(id = "a", name = "n", slug = "n", revision = 0, updatedAt = 0))
                    val floor = bus.oldestRetainedRevision()!!
                    staleCursorFloor(bus, lastEventId = floor).shouldBeNull()
                }
            }
        }

        test("returns the current floor when lastEventId is behind it") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                runTest {
                    // Publish past the 256-deep replay buffer so DROP_OLDEST evicts revision 1 — the
                    // exact overflow the SERVER-SYNC-02 race exploits: a burst landing between a
                    // staleness snapshot and actual subscription attach can advance this same floor.
                    repeat(300) { i -> repo.upsert(Tag("tag-$i", "n$i", "n$i", 0, 0)) }
                    val floor = bus.oldestRetainedRevision()!!
                    floor shouldBeGreaterThanOrEqual 2L

                    staleCursorFloor(bus, lastEventId = 1L) shouldBe floor
                }
            }
        }
    })
