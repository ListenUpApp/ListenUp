@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.test.runTest

/**
 * Bus mechanics, driven through a real [TagRepository] so events carry the
 * type-bound [BusEvent.repo] reference produced by the canonical publish path.
 */
class ChangeBusTest :
    FunSpec({

        test("publish then subscribe receives the event") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                runTest {
                    val deferred = async { bus.subscribe().first() }
                    advanceUntilIdle()
                    repo.upsert(Tag(id = "a", name = "n", slug = "n", revision = 0, updatedAt = 0))
                    val busEvent = deferred.await()
                    busEvent.repo.domainName shouldBe "tags"
                    busEvent.event.id shouldBe "a"
                }
            }
        }

        test("multiple subscribers each receive every event") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                runTest {
                    val sub1 = async { bus.subscribe().take(2).toList() }
                    val sub2 = async { bus.subscribe().take(2).toList() }
                    advanceUntilIdle()
                    repo.upsert(Tag(id = "a", name = "n1", slug = "n1", revision = 0, updatedAt = 0))
                    repo.upsert(Tag(id = "b", name = "n2", slug = "n2", revision = 0, updatedAt = 0))

                    val r1 = sub1.await()
                    val r2 = sub2.await()
                    r1 shouldHaveSize 2
                    r2 shouldHaveSize 2
                    r1.map { it.event.id } shouldBe listOf("a", "b")
                    r2.map { it.event.id } shouldBe listOf("a", "b")
                }
            }
        }

        test("oldestRetainedRevision tracks the lowest in-buffer event") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                runTest {
                    bus.oldestRetainedRevision() shouldBe null
                    repo.upsert(Tag(id = "a", name = "x", slug = "x", revision = 0, updatedAt = 0))
                    repo.upsert(Tag(id = "b", name = "y", slug = "y", revision = 0, updatedAt = 0))
                    bus.oldestRetainedRevision()!! shouldBeGreaterThanOrEqual 1L
                }
            }
        }

        test("BusEvent is type-bound to its source repository") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                runTest {
                    val deferred = async { bus.subscribe().first() }
                    advanceUntilIdle()
                    repo.upsert(Tag(id = "a", name = "n", slug = "n", revision = 0, updatedAt = 0))
                    val busEvent = deferred.await()
                    busEvent.repo.shouldBeInstanceOf<TagRepository>()
                    busEvent.repo shouldBe repo
                }
            }
        }

        test("publish carries an optional userId on the BusEvent") {
            withSqlDatabase {
                val bus = ChangeBus()
                val fakeRepo = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                val fakeEvent =
                    SyncEvent.Created(
                        id = "x",
                        revision = 1L,
                        occurredAt = 0L,
                        payload = Tag(id = "x", name = "e1", slug = "e1", revision = 1, updatedAt = 0),
                    )
                val fakeEvent2 =
                    SyncEvent.Created(
                        id = "y",
                        revision = 2L,
                        occurredAt = 0L,
                        payload = Tag(id = "y", name = "e2", slug = "e2", revision = 2, updatedAt = 0),
                    )
                runTest {
                    val sub = async { bus.subscribe().take(2).toList() }
                    advanceUntilIdle()
                    bus.publish(repo = fakeRepo, event = fakeEvent, userId = "u1")
                    bus.publish(repo = fakeRepo, event = fakeEvent2, userId = null)
                    val events = sub.await()
                    events[0].userId shouldBe "u1"
                    events[1].userId shouldBe null
                }
            }
        }

        // The publish sequencer. A committed write's afterCommit hook runs *after* the COMMIT that
        // frees SQLite's write lock, so the next writer can take its revision and reach the bus
        // while the earlier hook is still queued. These two pin the reserve/release contract that
        // keeps that reordering off the live tail.
        test("a slot released out of turn waits behind its predecessor") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                val first = bus.reserve(repo = repo, event = tagCreated(id = "a", revision = 1L))
                val second = bus.reserve(repo = repo, event = tagCreated(id = "b", revision = 2L))

                // Revision 2 commits first — nothing may reach subscribers until revision 1 does.
                bus.release(second)
                bus.subscribe().replayCache.shouldBeEmpty()

                bus.release(first)
                bus.subscribe().replayCache.map { it.event.revision } shouldBe listOf(1L, 2L)
            }
        }

        test("a rolled-back slot releases the writes queued behind it") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                val rolledBack = bus.reserve(repo = repo, event = tagCreated(id = "a", revision = 1L))
                val committed = bus.reserve(repo = repo, event = tagCreated(id = "b", revision = 2L))

                bus.release(committed)
                bus.subscribe().replayCache.shouldBeEmpty()

                // The head write never commits. Its slot still has to resolve, or revision 2 — and
                // every write after it — would queue forever behind a slot that never comes.
                bus.discard(rolledBack)
                bus.subscribe().replayCache.map { it.event.id } shouldBe listOf("b")
            }
        }

        test("a rolled-back savepoint inside a committing transaction does not wedge the bus") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                // The per-book savepoint shape BookRepository uses: a child rolls back while its
                // enclosing transaction goes on to commit. SQLDelight hands BOTH of a child's hook
                // lists up to the enclosing transaction, so the slot reserved inside the doomed
                // savepoint is resolved by whichever list the parent ends up running — but if that
                // stopped holding, the slot would strand and every later write would queue behind it.
                sql.transaction {
                    runCatching {
                        sql.transaction {
                            emitInPublishOrder(bus = bus, repo = repo, event = tagCreated("ghost", 1L))
                            error("savepoint fails after reserving its slot")
                        }
                    }
                    sql.transaction {
                        emitInPublishOrder(bus = bus, repo = repo, event = tagCreated("sibling", 2L))
                    }
                }

                // The point of the test: whatever became of the doomed savepoint's slot, the bus is
                // still live — a later write reaches subscribers rather than queueing behind it.
                runTest {
                    repo.upsert(Tag(id = "after", name = "after", slug = "after", revision = 0, updatedAt = 0))
                }
                bus.subscribe().replayCache.map { it.event.id } shouldContain "after"
            }
        }

        test("a transaction that rolls back after reserving its slot does not wedge the bus") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                // A write that fails *after* registering its emit — a constraint that only bites at
                // COMMIT, or a later row in a bulk chunk. (The unique-slug rollback in
                // FirehosePublishAfterCommitTest throws inside writePayload, before the slot is ever
                // reserved, so it does not cover this path.) The slot must be resolved by the
                // rollback hook, or the next write queues behind it forever.
                runCatching {
                    sql.transaction {
                        emitInPublishOrder(bus = bus, repo = repo, event = tagCreated("doomed", 1L))
                        error("write fails after reserving its slot")
                    }
                }

                runTest {
                    repo.upsert(Tag(id = "after", name = "after", slug = "after", revision = 0, updatedAt = 0))
                }
                bus.subscribe().replayCache.map { it.event.id } shouldContain "after"
            }
        }

        test("a stranded slot is aged out so the next single write still reaches the bus") {
            withSqlDatabase {
                // A quiet server is the case the count bound cannot serve: strand the head and
                // nothing more is published until 256 further writes arrive, which may be days.
                val time = TestTimeSource()
                val bus = ChangeBus(timeSource = time)
                val repo = TagRepository(db = sql, bus = bus, registry = SyncRegistry())

                bus.reserve(repo = repo, event = tagCreated("stranded", 1L))
                bus.release(bus.reserve(repo = repo, event = tagCreated("held", 2L)))
                bus.subscribe().replayCache.shouldBeEmpty()

                time += 31.seconds

                // ONE subsequent write, not 256 — both it and the write it was holding up land.
                bus.release(bus.reserve(repo = repo, event = tagCreated("next", 3L)))
                bus.subscribe().replayCache.map { it.event.id } shouldBe listOf("held", "next")
            }
        }

        test("a stranded slot is abandoned rather than holding the bus silent forever") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = sql, bus = bus, registry = SyncRegistry())
                // Strand the head: reserved, never released, never discarded — what a throwing
                // afterCommit hook, or a COMMIT that throws before SQLDelight reaches its hooks,
                // leaves behind.
                bus.reserve(repo = repo, event = tagCreated("stranded", 1L))

                repeat(HELD_SLOT_VALVE + 1) { i ->
                    bus.release(bus.reserve(repo = repo, event = tagCreated("after-$i", (i + 2).toLong())))
                }

                // Silence would be a worse failure than reordering, so the queue gives up on its
                // stalled head and flushes what was waiting behind it. Without the valve nothing
                // here ever reaches a subscriber; the head slot holds all of it forever.
                bus.subscribe().replayCache.map { it.event.id } shouldContain "after-$HELD_SLOT_VALVE"
            }
        }
    })

/** Mirrors `MAX_HELD_SLOTS` in [ChangeBus] — the depth at which a stalled head is abandoned. */
private const val HELD_SLOT_VALVE = 256

/** A `tags` [SyncEvent.Created] carrying [revision], for driving the bus's sequencer directly. */
private fun tagCreated(
    id: String,
    revision: Long,
): SyncEvent.Created<Tag> =
    SyncEvent.Created(
        id = id,
        revision = revision,
        occurredAt = 0L,
        payload = Tag(id = id, name = id, slug = id, revision = revision, updatedAt = 0),
    )
