package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.TagHit
import com.calypsan.listenup.api.dto.TagSummary
import com.calypsan.listenup.api.error.TagError
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.core.TagId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Round-trips every Tag-domain DTO through [contractJson]. Catches field-name drift,
 * polymorphic-discriminator drift, and default-value regression before any pipeline
 * code runs.
 */
class TagContractTest :
    FunSpec({

        // ── Tag (sync DTO) ────────────────────────────────────────────────────

        test("Tag with slug round-trips") {
            val original =
                Tag(
                    id = "tag-abc123",
                    name = "Sci-Fi",
                    slug = "sci-fi",
                    revision = 1L,
                    updatedAt = 1_700_000_000_000L,
                    deletedAt = null,
                )
            roundTrip<Tag>(original) shouldBe original
        }

        test("Tag tombstone (deletedAt non-null) round-trips") {
            val original =
                Tag(
                    id = "tag-abc123",
                    name = "Fantasy",
                    slug = "fantasy",
                    revision = 3L,
                    updatedAt = 1_700_000_000_000L,
                    deletedAt = 1_700_001_000_000L,
                )
            roundTrip<Tag>(original) shouldBe original
        }

        // ── BookTagSyncPayload ────────────────────────────────────────────────

        test("BookTagSyncPayload round-trips with all fields populated") {
            val original =
                BookTagSyncPayload(
                    id = "a1b2c3d4e5f60718293a4b5c6d7e8f90",
                    bookId = "book-xyz",
                    tagId = "tag-abc123",
                    createdAt = 1_700_000_000_000L,
                    revision = 2L,
                    deletedAt = null,
                )
            roundTrip<BookTagSyncPayload>(original) shouldBe original
            original.id shouldBe "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        }

        test("BookTagSyncPayload tombstone round-trips") {
            val original =
                BookTagSyncPayload(
                    id = "b2c3d4e5f60718293a4b5c6d7e8f9001",
                    bookId = "book-xyz",
                    tagId = "tag-abc123",
                    createdAt = 1_700_000_000_000L,
                    revision = 4L,
                    deletedAt = 1_700_001_000_000L,
                )
            roundTrip<BookTagSyncPayload>(original) shouldBe original
            original.id shouldBe "b2c3d4e5f60718293a4b5c6d7e8f9001"
        }

        // ── TagSummary ────────────────────────────────────────────────────────

        test("TagSummary round-trips") {
            val original =
                TagSummary(
                    id = TagId("tag-abc123"),
                    slug = "sci-fi",
                    name = "Sci-Fi",
                    bookCount = 42L,
                )
            roundTrip<TagSummary>(original) shouldBe original
        }

        test("TagSummary with zero bookCount round-trips") {
            val original =
                TagSummary(
                    id = TagId("tag-empty"),
                    slug = "empty-tag",
                    name = "Empty Tag",
                    bookCount = 0L,
                )
            roundTrip<TagSummary>(original) shouldBe original
        }

        // ── TagHit ────────────────────────────────────────────────────────────

        test("TagHit round-trips") {
            val original =
                TagHit(
                    id = TagId("tag-abc123"),
                    slug = "sci-fi",
                    name = "Sci-Fi",
                    bookCount = 12L,
                )
            roundTrip<TagHit>(original) shouldBe original
        }

        // ── TagError subtypes ─────────────────────────────────────────────────

        test("TagError.NotFound round-trips with constant message and code") {
            val original = TagError.NotFound(correlationId = "req-1", debugInfo = "id=tag-xyz")
            val roundTripped = roundTrip<TagError>(original)
            roundTripped shouldBe original
            (roundTripped as TagError.NotFound).message shouldBe "Tag not found."
            roundTripped.code shouldBe "TAG_NOT_FOUND"
            roundTripped.isRetryable shouldBe false
        }

        test("TagError.InvalidName round-trips with constant message and code") {
            val original = TagError.InvalidName(correlationId = null, debugInfo = null)
            val roundTripped = roundTrip<TagError>(original)
            roundTripped shouldBe original
            (roundTripped as TagError.InvalidName).message shouldBe "Tag name is empty or contains only special characters."
            roundTripped.code shouldBe "TAG_INVALID_NAME"
            roundTripped.isRetryable shouldBe false
        }

        test("TagError.NameTooLong round-trips with constant message and code") {
            val original = TagError.NameTooLong(correlationId = "req-2", debugInfo = "length=65")
            val roundTripped = roundTrip<TagError>(original)
            roundTripped shouldBe original
            (roundTripped as TagError.NameTooLong).message shouldBe "Tag name exceeds the 64-character limit."
            roundTripped.code shouldBe "TAG_NAME_TOO_LONG"
            roundTripped.isRetryable shouldBe false
        }

        test("TagError.BookNotFound round-trips with constant message and code") {
            val original = TagError.BookNotFound(correlationId = "req-3", debugInfo = null)
            val roundTripped = roundTrip<TagError>(original)
            roundTripped shouldBe original
            (roundTripped as TagError.BookNotFound).message shouldBe "Book not found."
            roundTripped.code shouldBe "TAG_BOOK_NOT_FOUND"
            roundTripped.isRetryable shouldBe false
        }
    })

private inline fun <reified T : Any> roundTrip(value: T): T = contractJson.decodeFromString<T>(contractJson.encodeToString(value))
