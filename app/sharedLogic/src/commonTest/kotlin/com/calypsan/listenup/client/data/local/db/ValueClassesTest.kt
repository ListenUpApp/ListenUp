package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ChapterId
import com.calypsan.listenup.core.Timestamp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for type-safe value classes: BookId, ChapterId, Timestamp.
 *
 * These tests verify:
 * - Validation constraints
 * - toString behavior
 * - Timestamp arithmetic and comparison
 */
class ValueClassesTest :
    FunSpec({
        // ========== BookId Tests ==========

        test("BookId stores value correctly") {
            val bookId = BookId("book-123")
            bookId.value shouldBe "book-123"
        }

        test("BookId toString returns value") {
            val bookId = BookId("book-abc")
            bookId.toString() shouldBe "book-abc"
        }

        test("BookId rejects blank value") {
            shouldThrow<IllegalArgumentException> {
                BookId("")
            }
        }

        test("BookId rejects whitespace-only value") {
            shouldThrow<IllegalArgumentException> {
                BookId("   ")
            }
        }

        test("BookId fromString creates valid id") {
            val bookId = BookId.fromString("book-456")
            bookId.value shouldBe "book-456"
        }

        test("BookId equality works correctly") {
            val id1 = BookId("book-1")
            val id2 = BookId("book-1")
            val id3 = BookId("book-2")

            id1 shouldBe id2
            id3 shouldNotBe id1
        }

        // ========== ChapterId Tests ==========

        test("ChapterId stores value correctly") {
            val chapterId = ChapterId("chapter-1")
            chapterId.value shouldBe "chapter-1"
        }

        test("ChapterId toString returns value") {
            val chapterId = ChapterId("ch-abc")
            chapterId.toString() shouldBe "ch-abc"
        }

        test("ChapterId rejects blank value") {
            shouldThrow<IllegalArgumentException> {
                ChapterId("")
            }
        }

        test("ChapterId rejects whitespace-only value") {
            shouldThrow<IllegalArgumentException> {
                ChapterId("   ")
            }
        }

        // ========== Timestamp Tests ==========

        test("Timestamp stores epoch millis correctly") {
            val ts = Timestamp(1_700_000_000_000L)
            ts.epochMillis shouldBe 1_700_000_000_000L
        }

        test("Timestamp toString returns epoch millis string") {
            val ts = Timestamp(1_234_567_890L)
            ts.toString() shouldBe "1234567890"
        }

        test("Timestamp now returns current time") {
            val before = Clock.System.now().toEpochMilliseconds()
            val ts = Timestamp.now()
            val after = Clock.System.now().toEpochMilliseconds()

            (ts.epochMillis >= before) shouldBe true
            (ts.epochMillis <= after) shouldBe true
        }

        test("Timestamp compareTo works correctly") {
            val earlier = Timestamp(1_000L)
            val later = Timestamp(2_000L)

            (earlier < later) shouldBe true
            (later > earlier) shouldBe true
            Timestamp(1_000L).compareTo(Timestamp(1_000L)) shouldBe 0
        }

        test("Timestamp minus calculates duration between timestamps") {
            val earlier = Timestamp(1_000_000L)
            val later = Timestamp(1_000_000L + 3_600_000L) // 1 hour later (3,600,000ms = 1 hour)

            val duration = later - earlier
            duration shouldBe 1.hours
        }

        test("Timestamp plus adds duration") {
            val ts = Timestamp(1_000_000L)
            val result = ts + 1.hours

            // 1_000_000 + 3_600_000 = 4_600_000
            result.epochMillis shouldBe 4_600_000L
        }

        test("Timestamp plus handles milliseconds") {
            val ts = Timestamp(1_000_000L)
            val result = ts + 500.milliseconds

            result.epochMillis shouldBe 1_000_500L
        }

        test("Timestamp toIsoString formats correctly") {
            // Unix timestamp 0 should format to epoch
            val ts = Timestamp(0L)
            ts.toIsoString() shouldBe "1970-01-01T00:00:00Z"
        }
    })
