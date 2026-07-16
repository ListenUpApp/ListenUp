package com.calypsan.listenup.client.presentation.storyworld

import com.calypsan.listenup.client.domain.model.PlaybackPosition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/** Minimal [PlaybackPosition] builder — only the fields [FrontierGate] reads vary per test. */
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

/** A log-entry-shaped fixture: the [Pair] `anchorOf` extracts from is `(bookId, positionMs)`. */
private data class Entry(
    val label: String,
    val bookId: String?,
    val positionMs: Long?,
)

private fun Entry.anchor(): Pair<String?, Long?> = bookId to positionMs

class FrontierGateTest :
    FunSpec({

        test("isVisible: null bookId is always visible (baseline entry)") {
            FrontierGate.isVisible(bookId = null, positionMs = 500L, positions = emptyMap()) shouldBe true
        }

        test("isVisible: missing position row (book never started) is not visible") {
            FrontierGate.isVisible(bookId = "book-1", positionMs = 0L, positions = emptyMap()) shouldBe false
        }

        test("isVisible: a finished book reveals everything, including beyond the frontier") {
            val positions = mapOf("book-1" to position(maxPositionMs = 1_000L, isFinished = true))
            FrontierGate.isVisible(bookId = "book-1", positionMs = 999_999L, positions = positions) shouldBe true
        }

        test("isVisible: null positionMs on a started book (via startedAtMs) is visible") {
            val positions = mapOf("book-1" to position(startedAtMs = 500L, maxPositionMs = 0))
            FrontierGate.isVisible(bookId = "book-1", positionMs = null, positions = positions) shouldBe true
        }

        test("isVisible: null positionMs on a started book (via maxPositionMs > 0) is visible") {
            val positions = mapOf("book-1" to position(startedAtMs = null, maxPositionMs = 1L))
            FrontierGate.isVisible(bookId = "book-1", positionMs = null, positions = positions) shouldBe true
        }

        test("isVisible: null positionMs on an unstarted book is not visible") {
            val positions = mapOf("book-1" to position(startedAtMs = null, maxPositionMs = 0))
            FrontierGate.isVisible(bookId = "book-1", positionMs = null, positions = positions) shouldBe false
        }

        test("isVisible: positionMs exactly at maxPositionMs is visible") {
            val positions = mapOf("book-1" to position(maxPositionMs = 10_000L))
            FrontierGate.isVisible(bookId = "book-1", positionMs = 10_000L, positions = positions) shouldBe true
        }

        test("isVisible: positionMs one millisecond beyond maxPositionMs is not visible") {
            val positions = mapOf("book-1" to position(maxPositionMs = 10_000L))
            FrontierGate.isVisible(bookId = "book-1", positionMs = 10_001L, positions = positions) shouldBe false
        }

        test("gate: reveal=true passes every item through with hiddenCount 0") {
            val entries =
                listOf(
                    Entry("a", "book-1", 999_999L),
                    Entry("b", null, null),
                    Entry("c", "book-2", 1L),
                )
            val result = FrontierGate.gate(entries, positions = emptyMap(), reveal = true) { it.anchor() }

            result.visible shouldBe entries
            result.hiddenCount shouldBe 0
        }

        test("gate: preserves input order among visible items") {
            val positions = mapOf("book-1" to position(maxPositionMs = 10_000L))
            val entries =
                listOf(
                    Entry("first", "book-1", 1_000L),
                    Entry("second", null, null),
                    Entry("third", "book-1", 5_000L),
                    Entry("fourth", "book-1", 50_000L), // beyond frontier — hidden
                    Entry("fifth", "book-1", 9_000L),
                )
            val result = FrontierGate.gate(entries, positions, reveal = false) { it.anchor() }

            result.visible.map { it.label } shouldBe listOf("first", "second", "third", "fifth")
            result.hiddenCount shouldBe 1
        }

        test("gate: hiddenCount counts every item the filter removed") {
            val positions = mapOf("book-1" to position(maxPositionMs = 1_000L))
            val entries =
                listOf(
                    Entry("visible", "book-1", 500L),
                    Entry("hidden-1", "book-1", 2_000L), // beyond book-1's frontier
                    Entry("hidden-2", "book-2", 0L), // book-2 never started at all
                    Entry("hidden-3", "book-3", null), // book-3 never started, null position
                )
            val result = FrontierGate.gate(entries, positions, reveal = false) { it.anchor() }

            result.visible.map { it.label } shouldBe listOf("visible")
            result.hiddenCount shouldBe 3
        }

        // ──────────────────────────────────────────────────────────────────────
        // Property test. kotest-property is not on :sharedLogic's commonTest classpath
        // (only :contract has it wired up) — falling back to a seeded Random loop per
        // the task brief rather than adding a new Gradle dependency in this task.
        // ──────────────────────────────────────────────────────────────────────

        test("property: an unfinished started book never reveals an item beyond its frontier when reveal=false") {
            val random = Random(42)
            repeat(500) {
                val maxPositionMs = random.nextLong(0L, 1_000_000L)
                val startedAtMs = if (random.nextBoolean()) random.nextLong(0L, 1_000_000L) else null
                val positions = mapOf("book-1" to position(maxPositionMs = maxPositionMs, startedAtMs = startedAtMs))

                val entries =
                    (0 until 20).map { i ->
                        val positionMs =
                            when (random.nextInt(3)) {
                                0 -> null
                                1 -> random.nextLong(0L, maxPositionMs.coerceAtLeast(1L))
                                else -> maxPositionMs + 1 + random.nextLong(0L, 1_000_000L)
                            }
                        Entry("e$i", "book-1", positionMs)
                    }

                val result = FrontierGate.gate(entries, positions, reveal = false) { it.anchor() }

                result.visible.forEach { entry ->
                    val positionMs = entry.positionMs
                    if (positionMs != null) {
                        (positionMs <= maxPositionMs) shouldBe true
                    }
                }
                (result.visible.size + result.hiddenCount) shouldBe entries.size
            }
        }

        test("property: visible size plus hiddenCount always equals the input size") {
            val random = Random(1234)
            repeat(500) {
                val bookCount = random.nextInt(1, 4)
                val positions =
                    (1..bookCount).associate { i ->
                        "book-$i" to
                            position(
                                bookId = "book-$i",
                                maxPositionMs = random.nextLong(0L, 500_000L),
                                startedAtMs = if (random.nextBoolean()) 1L else null,
                                isFinished = random.nextBoolean(),
                            )
                    }
                val entries =
                    (0 until 30).map { i ->
                        val bookId = if (random.nextBoolean()) "book-${random.nextInt(1, bookCount + 1)}" else null
                        val positionMs = if (random.nextBoolean()) random.nextLong(0L, 600_000L) else null
                        Entry("e$i", bookId, positionMs)
                    }

                val reveal = random.nextBoolean()
                val result = FrontierGate.gate(entries, positions, reveal = reveal) { it.anchor() }

                (result.visible.size + result.hiddenCount) shouldBe entries.size
            }
        }
    })
