package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.MoodSummary
import com.calypsan.listenup.api.error.MoodError
import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.core.MoodId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Round-trips every Mood-domain DTO through [contractJson]. Catches field-name drift,
 * polymorphic-discriminator drift, and default-value regression before any pipeline
 * code runs.
 *
 * Mirror of [TagContractTest] — moods are the affective sibling of tags (flat, syncable,
 * soft-delete) and the book's moods reach the client via the mood sync stream, so the
 * same wire-shape guarantees apply.
 */
class MoodContractTest :
    FunSpec({

        // ── Mood (sync DTO) ───────────────────────────────────────────────────

        test("Mood with slug round-trips") {
            val original =
                Mood(
                    id = "mood-abc123",
                    name = "Feel-Good",
                    slug = "feel-good",
                    revision = 1L,
                    updatedAt = 1_700_000_000_000L,
                    deletedAt = null,
                )
            roundTrip<Mood>(original) shouldBe original
        }

        test("Mood tombstone (deletedAt non-null) round-trips") {
            val original =
                Mood(
                    id = "mood-abc123",
                    name = "Tense",
                    slug = "tense",
                    revision = 3L,
                    updatedAt = 1_700_000_000_000L,
                    deletedAt = 1_700_001_000_000L,
                )
            roundTrip<Mood>(original) shouldBe original
        }

        // ── BookMoodSyncPayload ───────────────────────────────────────────────

        test("BookMoodSyncPayload round-trips with all fields populated") {
            val original =
                BookMoodSyncPayload(
                    id = "a1b2c3d4e5f60718293a4b5c6d7e8f90",
                    bookId = "book-xyz",
                    moodId = "mood-abc123",
                    createdAt = 1_700_000_000_000L,
                    revision = 2L,
                    deletedAt = null,
                )
            roundTrip<BookMoodSyncPayload>(original) shouldBe original
            original.id shouldBe "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        }

        test("BookMoodSyncPayload tombstone round-trips") {
            val original =
                BookMoodSyncPayload(
                    id = "b2c3d4e5f60718293a4b5c6d7e8f9001",
                    bookId = "book-xyz",
                    moodId = "mood-abc123",
                    createdAt = 1_700_000_000_000L,
                    revision = 4L,
                    deletedAt = 1_700_001_000_000L,
                )
            roundTrip<BookMoodSyncPayload>(original) shouldBe original
            original.id shouldBe "b2c3d4e5f60718293a4b5c6d7e8f9001"
        }

        // ── MoodSummary ───────────────────────────────────────────────────────

        test("MoodSummary round-trips") {
            val original =
                MoodSummary(
                    id = MoodId("mood-abc123"),
                    slug = "feel-good",
                    name = "Feel-Good",
                    bookCount = 42L,
                )
            roundTrip<MoodSummary>(original) shouldBe original
        }

        test("MoodSummary with zero bookCount round-trips") {
            val original =
                MoodSummary(
                    id = MoodId("mood-empty"),
                    slug = "empty-mood",
                    name = "Empty Mood",
                    bookCount = 0L,
                )
            roundTrip<MoodSummary>(original) shouldBe original
        }

        // ── MoodError subtypes ────────────────────────────────────────────────

        test("MoodError.NotFound round-trips with constant message and code") {
            val original = MoodError.NotFound(correlationId = "req-1", debugInfo = "id=mood-xyz")
            val roundTripped = roundTrip<MoodError>(original)
            roundTripped shouldBe original
            (roundTripped as MoodError.NotFound).message shouldBe "Mood not found."
            roundTripped.code shouldBe "MOOD_NOT_FOUND"
            roundTripped.isRetryable shouldBe false
        }

        test("MoodError.InvalidName round-trips with constant message and code") {
            val original = MoodError.InvalidName(correlationId = null, debugInfo = null)
            val roundTripped = roundTrip<MoodError>(original)
            roundTripped shouldBe original
            (roundTripped as MoodError.InvalidName).message shouldBe "Mood name is empty or contains only special characters."
            roundTripped.code shouldBe "MOOD_INVALID_NAME"
            roundTripped.isRetryable shouldBe false
        }

        test("MoodError.NameTooLong round-trips with constant message and code") {
            val original = MoodError.NameTooLong(correlationId = "req-2", debugInfo = "length=65")
            val roundTripped = roundTrip<MoodError>(original)
            roundTripped shouldBe original
            (roundTripped as MoodError.NameTooLong).message shouldBe "Mood name exceeds the 64-character limit."
            roundTripped.code shouldBe "MOOD_NAME_TOO_LONG"
            roundTripped.isRetryable shouldBe false
        }

        test("MoodError.BookNotFound round-trips with constant message and code") {
            val original = MoodError.BookNotFound(correlationId = "req-3", debugInfo = null)
            val roundTripped = roundTrip<MoodError>(original)
            roundTripped shouldBe original
            (roundTripped as MoodError.BookNotFound).message shouldBe "Book not found."
            roundTripped.code shouldBe "MOOD_BOOK_NOT_FOUND"
            roundTripped.isRetryable shouldBe false
        }
    })

private inline fun <reified T : Any> roundTrip(value: T): T = contractJson.decodeFromString<T>(contractJson.encodeToString(value))
