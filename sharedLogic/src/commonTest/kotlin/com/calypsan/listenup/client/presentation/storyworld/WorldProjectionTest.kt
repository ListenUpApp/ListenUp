package com.calypsan.listenup.client.presentation.storyworld

import com.calypsan.listenup.api.sync.WorldEventSource
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.WorldEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/** Minimal [PlaybackPosition] builder — only the fields the fold reads vary per test. */
private fun position(
    bookId: String = "book-1",
    maxPositionMs: Long = 0,
    startedAtMs: Long? = null,
    isFinished: Boolean = false,
): PlaybackPosition =
    PlaybackPosition(
        bookId = bookId,
        positionMs = 0L,
        maxPositionMs = maxPositionMs,
        playbackSpeed = 1.0f,
        hasCustomSpeed = false,
        updatedAtMs = 0L,
        syncedAtMs = null,
        lastPlayedAtMs = null,
        isFinished = isFinished,
        startedAtMs = startedAtMs,
    )

/** Minimal [WorldEvent] builder. [mentionIds] defaults to the subject/object union per the mirror guarantee. */
private fun event(
    id: String,
    type: WorldEventType,
    bookId: String? = null,
    positionMs: Long? = null,
    subjectEntityId: String? = null,
    objectEntityId: String? = null,
    mentionIds: List<String> = listOfNotNull(subjectEntityId, objectEntityId),
    text: String = "text-$id",
): WorldEvent =
    WorldEvent(
        id = id,
        homeBookId = "home-book",
        bookId = bookId,
        positionMs = positionMs,
        type = type,
        text = text,
        subjectEntityId = subjectEntityId,
        objectEntityId = objectEntityId,
        mentionIds = mentionIds,
        source = WorldEventSource.MANUAL,
    )

/** Only the facts inertness/leak-proofing properties care about — excludes eventCount/lastSeen. */
private fun WorldState.factsOnly(): Map<String, Triple<Boolean, LocationFact, WorldEvent?>> = entities.mapValues { (_, entity) -> Triple(entity.inScene, entity.location, entity.statusNote) }

/**
 * A random, structurally valid [WorldEvent] for property tests: [type] drives whether a subject
 * (and possibly an object) is present, and [mentionIds] always keeps the subject/object union
 * per the mirror guarantee ([WorldEvent.mentionIds] docs).
 */
private fun randomEvent(
    random: Random,
    index: Int,
    bookIds: List<String?>,
    entityIds: List<String>,
    types: List<WorldEventType> = WorldEventType.entries,
): WorldEvent {
    val type = types[random.nextInt(types.size)]
    val bookId = bookIds[random.nextInt(bookIds.size)]
    val positionMs = if (bookId == null) null else random.nextLong(0L, 1_000L)
    val subjectEntityId = if (random.nextBoolean()) entityIds.random(random) else null
    val objectEntityId =
        if (subjectEntityId != null && random.nextBoolean()) {
            entityIds.filterNot { it == subjectEntityId }.random(random)
        } else {
            null
        }
    val extraMention = if (random.nextBoolean()) entityIds.random(random) else null
    val mentionIds = listOfNotNull(subjectEntityId, objectEntityId, extraMention).distinct()

    return event(
        id = "evt-$index",
        type = type,
        bookId = bookId,
        positionMs = positionMs,
        subjectEntityId = subjectEntityId,
        objectEntityId = objectEntityId,
        mentionIds = mentionIds,
    )
}

class WorldProjectionTest :
    FunSpec({

        // ──────────────────────────────────────────────────────────────────────
        // Example-based
        // ──────────────────────────────────────────────────────────────────────

        test("ENTERS_SCENE then EXITS_SCENE leaves the subject not in scene") {
            val events =
                listOf(
                    event(id = "e1", type = WorldEventType.ENTERS_SCENE, bookId = "book-1", positionMs = 100L, subjectEntityId = "char-1"),
                    event(id = "e2", type = WorldEventType.EXITS_SCENE, bookId = "book-1", positionMs = 200L, subjectEntityId = "char-1"),
                )
            val clock = FoldClock.PerBookClock("book-1", mapOf("book-1" to position(maxPositionMs = 1_000L)))

            WorldProjection.project(events, clock).entities["char-1"]?.inScene shouldBe false
        }

        test("fold order follows position, not authored list order") {
            // Authored with EXITS listed first but at a LATER position — position order wins,
            // so the final state is the ENTERS (position 200) landing last in fold order.
            val events =
                listOf(
                    event(
                        id = "e-exit",
                        type = WorldEventType.EXITS_SCENE,
                        bookId = "book-1",
                        positionMs = 100L,
                        subjectEntityId = "char-1",
                    ),
                    event(
                        id = "e-enter",
                        type = WorldEventType.ENTERS_SCENE,
                        bookId = "book-1",
                        positionMs = 200L,
                        subjectEntityId = "char-1",
                    ),
                )
            val clock = FoldClock.PerBookClock("book-1", mapOf("book-1" to position(maxPositionMs = 1_000L)))

            WorldProjection.project(events, clock).entities["char-1"]?.inScene shouldBe true
        }

        test("MOVES_TO sets location to Known") {
            val events =
                listOf(
                    event(
                        id = "e1",
                        type = WorldEventType.MOVES_TO,
                        bookId = "book-1",
                        positionMs = 100L,
                        subjectEntityId = "char-1",
                        objectEntityId = "loc-1",
                    ),
                )
            val clock = FoldClock.PerBookClock("book-1", mapOf("book-1" to position(maxPositionMs = 1_000L)))

            WorldProjection.project(events, clock).entities["char-1"]?.location shouldBe LocationFact.Known("loc-1")
        }

        test("DEPARTS sets EnRoute and preserves inScene from an earlier ENTERS_SCENE") {
            val events =
                listOf(
                    event(id = "e1", type = WorldEventType.ENTERS_SCENE, bookId = "book-1", positionMs = 100L, subjectEntityId = "char-1"),
                    event(
                        id = "e2",
                        type = WorldEventType.DEPARTS,
                        bookId = "book-1",
                        positionMs = 200L,
                        subjectEntityId = "char-1",
                        objectEntityId = "loc-1",
                    ),
                )
            val clock = FoldClock.PerBookClock("book-1", mapOf("book-1" to position(maxPositionMs = 1_000L)))

            val state = WorldProjection.project(events, clock).entities["char-1"]
            state?.location shouldBe LocationFact.EnRoute("loc-1")
            state?.inScene shouldBe true
        }

        test("MOVES_TO with a null object leaves location unchanged") {
            val events =
                listOf(
                    event(
                        id = "e1",
                        type = WorldEventType.MOVES_TO,
                        bookId = "book-1",
                        positionMs = 100L,
                        subjectEntityId = "char-1",
                        objectEntityId = null,
                        mentionIds = listOf("char-1"),
                    ),
                )
            val clock = FoldClock.PerBookClock("book-1", mapOf("book-1" to position(maxPositionMs = 1_000L)))

            WorldProjection.project(events, clock).entities["char-1"]?.location shouldBe LocationFact.Unknown
        }

        test("statusNote reflects the latest NOTE by fold (position) order, not by id ordering") {
            val events =
                listOf(
                    event(
                        id = "z-note",
                        type = WorldEventType.NOTE,
                        bookId = "book-1",
                        positionMs = 100L,
                        mentionIds = listOf("char-1"),
                        text = "older by position, newer id",
                    ),
                    event(
                        id = "a-note",
                        type = WorldEventType.NOTE,
                        bookId = "book-1",
                        positionMs = 200L,
                        mentionIds = listOf("char-1"),
                        text = "newer by position, older id",
                    ),
                )
            val clock = FoldClock.PerBookClock("book-1", mapOf("book-1" to position(maxPositionMs = 1_000L)))

            WorldProjection
                .project(events, clock)
                .entities["char-1"]
                ?.statusNote
                ?.text shouldBe "newer by position, older id"
        }

        test("baseline (null-book) events fold before book-anchored events") {
            val events =
                listOf(
                    event(
                        id = "book-event",
                        type = WorldEventType.NOTE,
                        bookId = "book-1",
                        positionMs = 0L,
                        mentionIds = listOf("char-1"),
                        text = "book note",
                    ),
                    event(
                        id = "baseline-event",
                        type = WorldEventType.NOTE,
                        bookId = null,
                        mentionIds = listOf("char-1"),
                        text = "baseline note",
                    ),
                )
            val clock = FoldClock.PerBookClock("book-1", mapOf("book-1" to position(maxPositionMs = 1_000L)))

            // Baseline has orderIndex -1 (folds first); the book event folds second and wins as statusNote.
            WorldProjection
                .project(events, clock)
                .entities["char-1"]
                ?.statusNote
                ?.text shouldBe "book note"
        }

        test("OrderedClock excludes events anchored to books outside the reading order") {
            val events =
                listOf(
                    event(id = "e1", type = WorldEventType.NOTE, bookId = "book-99", positionMs = 0L, mentionIds = listOf("char-1")),
                )
            val clock =
                FoldClock.OrderedClock(
                    orderedBookIds = listOf("book-1", "book-2"),
                    frontiers = mapOf("book-99" to position(bookId = "book-99", maxPositionMs = 1_000L)),
                )

            val state = WorldProjection.project(events, clock)
            state.entities shouldBe emptyMap()
            state.foldedEventCount shouldBe 0
        }

        test("PerBookClock excludes events anchored to a different book") {
            val events =
                listOf(
                    event(id = "e1", type = WorldEventType.NOTE, bookId = "book-2", positionMs = 0L, mentionIds = listOf("char-1")),
                )
            val clock =
                FoldClock.PerBookClock(
                    bookId = "book-1",
                    frontiers = mapOf("book-2" to position(bookId = "book-2", maxPositionMs = 1_000L)),
                )

            WorldProjection.project(events, clock).entities shouldBe emptyMap()
        }

        test("DIES does not flip inScene") {
            val events =
                listOf(
                    event(id = "e1", type = WorldEventType.ENTERS_SCENE, bookId = "book-1", positionMs = 100L, subjectEntityId = "char-1"),
                    event(id = "e2", type = WorldEventType.DIES, bookId = "book-1", positionMs = 200L, subjectEntityId = "char-1"),
                )
            val clock = FoldClock.PerBookClock("book-1", mapOf("book-1" to position(maxPositionMs = 1_000L)))

            WorldProjection.project(events, clock).entities["char-1"]?.inScene shouldBe true
        }

        test("ITEM_TRANSFER does not touch location") {
            val events =
                listOf(
                    event(
                        id = "e1",
                        type = WorldEventType.MOVES_TO,
                        bookId = "book-1",
                        positionMs = 100L,
                        subjectEntityId = "char-1",
                        objectEntityId = "loc-1",
                    ),
                    event(
                        id = "e2",
                        type = WorldEventType.ITEM_TRANSFER,
                        bookId = "book-1",
                        positionMs = 200L,
                        subjectEntityId = "char-1",
                        objectEntityId = "item-1",
                    ),
                )
            val clock = FoldClock.PerBookClock("book-1", mapOf("book-1" to position(maxPositionMs = 1_000L)))

            WorldProjection.project(events, clock).entities["char-1"]?.location shouldBe LocationFact.Known("loc-1")
        }

        test("withPlayheadRaised reveals exactly the events between the old frontier and the new playhead") {
            val events =
                listOf(
                    event(
                        id = "e1",
                        type = WorldEventType.NOTE,
                        bookId = "book-1",
                        positionMs = 100L,
                        mentionIds = listOf("char-1"),
                        text = "within old frontier",
                    ),
                    event(
                        id = "e2",
                        type = WorldEventType.NOTE,
                        bookId = "book-1",
                        positionMs = 500L,
                        mentionIds = listOf("char-1"),
                        text = "revealed by raise",
                    ),
                    event(
                        id = "e3",
                        type = WorldEventType.NOTE,
                        bookId = "book-1",
                        positionMs = 900L,
                        mentionIds = listOf("char-1"),
                        text = "still beyond playhead",
                    ),
                )
            val baseClock = FoldClock.PerBookClock("book-1", mapOf("book-1" to position(maxPositionMs = 200L)))

            WorldProjection.project(events, baseClock).foldedEventCount shouldBe 1

            val raisedClock = WorldProjection.withPlayheadRaised(baseClock, "book-1", playheadMs = 600L)
            val after = WorldProjection.project(events, raisedClock)

            after.foldedEventCount shouldBe 2
            after.entities["char-1"]?.statusNote?.text shouldBe "revealed by raise"
        }

        test("withPlayheadRaised on a never-started book reveals events up to the playhead") {
            val events =
                listOf(
                    event(id = "e1", type = WorldEventType.NOTE, bookId = "book-1", positionMs = 100L, mentionIds = listOf("char-1")),
                    event(id = "e2", type = WorldEventType.NOTE, bookId = "book-1", positionMs = 900L, mentionIds = listOf("char-1")),
                )
            val baseClock = FoldClock.PerBookClock("book-1", emptyMap())

            WorldProjection.project(events, baseClock).foldedEventCount shouldBe 0

            val raisedClock = WorldProjection.withPlayheadRaised(baseClock, "book-1", playheadMs = 500L)

            WorldProjection.project(events, raisedClock).foldedEventCount shouldBe 1
        }

        // ──────────────────────────────────────────────────────────────────────
        // Property tests. kotest-property is not on :sharedLogic's commonTest classpath
        // (only :contract has it wired up) — falling back to a seeded Random loop per
        // the FrontierGateTest precedent rather than adding a new Gradle dependency here.
        // ──────────────────────────────────────────────────────────────────────

        test("property: permuting the event list before folding produces an identical WorldState") {
            val random = Random(42)
            val bookIds = listOf(null, "book-1", "book-2")
            val entityIds = listOf("char-1", "char-2", "char-3", "loc-1", "loc-2")
            val clock =
                FoldClock.OrderedClock(
                    orderedBookIds = listOf("book-1", "book-2"),
                    frontiers =
                        mapOf(
                            "book-1" to position(bookId = "book-1", maxPositionMs = 1_000L, startedAtMs = 1L),
                            "book-2" to position(bookId = "book-2", maxPositionMs = 1_000L, startedAtMs = 1L),
                        ),
                )

            repeat(500) {
                val events = (0 until 15).map { i -> randomEvent(random, i, bookIds, entityIds) }
                val baseline = WorldProjection.project(events, clock)

                repeat(5) {
                    WorldProjection.project(events.shuffled(random), clock) shouldBe baseline
                }
            }
        }

        test("property: events beyond the frontier never influence the projected WorldState") {
            val random = Random(7)
            val entityIds = listOf("char-1", "char-2", "loc-1")

            repeat(500) {
                val maxPositionMs = random.nextLong(0L, 1_000L)
                val frontiers = mapOf("book-1" to position(bookId = "book-1", maxPositionMs = maxPositionMs, startedAtMs = 1L))
                val clock = FoldClock.PerBookClock("book-1", frontiers)
                val events = (0 until 15).map { i -> randomEvent(random, i, listOf(null, "book-1"), entityIds) }
                val safeEvents = events.filter { FrontierGate.isVisible(it.bookId, it.positionMs, frontiers) }

                WorldProjection.project(events, clock) shouldBe WorldProjection.project(safeEvents, clock)
            }
        }

        test("property: reserved-type events never change inScene, location, or statusNote for existing entities") {
            val random = Random(99)
            val entityIds = listOf("char-1", "char-2", "loc-1")
            val nonReservedTypes =
                listOf(
                    WorldEventType.NOTE,
                    WorldEventType.ENTERS_SCENE,
                    WorldEventType.EXITS_SCENE,
                    WorldEventType.MOVES_TO,
                    WorldEventType.DEPARTS,
                )
            val reservedTypes =
                listOf(
                    WorldEventType.ALIAS,
                    WorldEventType.BORN,
                    WorldEventType.DIES,
                    WorldEventType.ITEM_TRANSFER,
                    WorldEventType.RELATIONSHIP_CHANGE,
                )
            val frontiers = mapOf("book-1" to position(bookId = "book-1", maxPositionMs = 1_000L, startedAtMs = 1L))
            val clock = FoldClock.PerBookClock("book-1", frontiers)

            repeat(500) {
                val baseEvents = (0 until 10).map { i -> randomEvent(random, i, listOf(null, "book-1"), entityIds, nonReservedTypes) }
                val reservedEvents = (10 until 15).map { i -> randomEvent(random, i, listOf(null, "book-1"), entityIds, reservedTypes) }

                val baseline = WorldProjection.project(baseEvents, clock).factsOnly()
                val withReserved = WorldProjection.project(baseEvents + reservedEvents, clock).factsOnly()

                baseline.forEach { (entityId, facts) -> withReserved[entityId] shouldBe facts }
            }
        }

        test("property: a single-book log folds identically under PerBookClock and OrderedClock(listOf(book))") {
            val random = Random(2024)
            val entityIds = listOf("char-1", "char-2", "loc-1")

            repeat(500) {
                val events = (0 until 12).map { i -> randomEvent(random, i, listOf(null, "book-1"), entityIds) }
                val maxPositionMs = random.nextLong(0L, 1_000L)
                val frontiers = mapOf("book-1" to position(bookId = "book-1", maxPositionMs = maxPositionMs, startedAtMs = 1L))

                val perBook = WorldProjection.project(events, FoldClock.PerBookClock("book-1", frontiers))
                val ordered = WorldProjection.project(events, FoldClock.OrderedClock(listOf("book-1"), frontiers))

                ordered shouldBe perBook
            }
        }
    })
