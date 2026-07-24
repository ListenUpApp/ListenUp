package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the round-trip behaviour, stable `@SerialName` discriminators, and body-level
 * message constants for all seven [GenreError] subtypes. Encoding through
 * [AppError.serializer] exercises the polymorphic discriminator path so we catch
 * `@SerialName` drift the moment it happens.
 */
class GenresErrorContractTest :
    FunSpec({

        // ── GenreError.NotFound ───────────────────────────────────────────────

        test("should round-trip GenreError.NotFound through AppError serializer") {
            val original: AppError =
                GenreError.NotFound(correlationId = "req-1", debugInfo = "id=g-missing")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should embed stable discriminator for GenreError.NotFound") {
            val json = contractJson.encodeToString(AppError.serializer(), GenreError.NotFound())
            json.contains("\"GenreError.NotFound\"") shouldBe true
        }

        test("should have constant body-level message for GenreError.NotFound") {
            GenreError.NotFound().message shouldBe
                GenreError.NotFound(debugInfo = "x").message
        }

        test("should mark GenreError.NotFound as not retryable") {
            GenreError.NotFound().isRetryable shouldBe false
        }

        // ── GenreError.InvalidInput ───────────────────────────────────────────

        test("should round-trip GenreError.InvalidInput through AppError serializer") {
            val original: AppError =
                GenreError.InvalidInput(correlationId = "req-2", debugInfo = "name=blank")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should embed stable discriminator for GenreError.InvalidInput") {
            val json = contractJson.encodeToString(AppError.serializer(), GenreError.InvalidInput())
            json.contains("\"GenreError.InvalidInput\"") shouldBe true
        }

        test("should have constant body-level message for GenreError.InvalidInput") {
            GenreError.InvalidInput().message shouldBe
                GenreError.InvalidInput(debugInfo = "y").message
        }

        test("should mark GenreError.InvalidInput as not retryable") {
            GenreError.InvalidInput().isRetryable shouldBe false
        }

        // ── GenreError.HasDescendants ─────────────────────────────────────────

        test("should round-trip GenreError.HasDescendants through AppError serializer") {
            val original: AppError =
                GenreError.HasDescendants(debugInfo = "id=g-fiction descendants=12")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should embed stable discriminator for GenreError.HasDescendants") {
            val json = contractJson.encodeToString(AppError.serializer(), GenreError.HasDescendants())
            json.contains("\"GenreError.HasDescendants\"") shouldBe true
        }

        test("should have constant body-level message for GenreError.HasDescendants") {
            GenreError.HasDescendants().message shouldBe
                GenreError.HasDescendants(debugInfo = "z").message
        }

        test("should mark GenreError.HasDescendants as not retryable") {
            GenreError.HasDescendants().isRetryable shouldBe false
        }

        // ── GenreError.MergeSelfTarget ────────────────────────────────────────

        test("should round-trip GenreError.MergeSelfTarget through AppError serializer") {
            val original: AppError =
                GenreError.MergeSelfTarget(debugInfo = "source=g1 target=g1")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should embed stable discriminator for GenreError.MergeSelfTarget") {
            val json = contractJson.encodeToString(AppError.serializer(), GenreError.MergeSelfTarget())
            json.contains("\"GenreError.MergeSelfTarget\"") shouldBe true
        }

        test("should have constant body-level message for GenreError.MergeSelfTarget") {
            GenreError.MergeSelfTarget().message shouldBe
                GenreError.MergeSelfTarget(debugInfo = "a").message
        }

        test("should mark GenreError.MergeSelfTarget as not retryable") {
            GenreError.MergeSelfTarget().isRetryable shouldBe false
        }

        // ── GenreError.MoveSelfDescendant ─────────────────────────────────────

        test("should round-trip GenreError.MoveSelfDescendant through AppError serializer") {
            val original: AppError =
                GenreError.MoveSelfDescendant(debugInfo = "id=g-fantasy newParent=g-fantasy")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should embed stable discriminator for GenreError.MoveSelfDescendant") {
            val json = contractJson.encodeToString(AppError.serializer(), GenreError.MoveSelfDescendant())
            json.contains("\"GenreError.MoveSelfDescendant\"") shouldBe true
        }

        test("should have constant body-level message for GenreError.MoveSelfDescendant") {
            GenreError.MoveSelfDescendant().message shouldBe
                GenreError.MoveSelfDescendant(debugInfo = "b").message
        }

        test("should mark GenreError.MoveSelfDescendant as not retryable") {
            GenreError.MoveSelfDescendant().isRetryable shouldBe false
        }

        // ── GenreError.UnmappedStringNotFound ─────────────────────────────────

        test("should round-trip GenreError.UnmappedStringNotFound through AppError serializer") {
            val original: AppError =
                GenreError.UnmappedStringNotFound(debugInfo = "raw='Bizarro Fiction'")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should embed stable discriminator for GenreError.UnmappedStringNotFound") {
            val json = contractJson.encodeToString(AppError.serializer(), GenreError.UnmappedStringNotFound())
            json.contains("\"GenreError.UnmappedStringNotFound\"") shouldBe true
        }

        test("should have constant body-level message for GenreError.UnmappedStringNotFound") {
            GenreError.UnmappedStringNotFound().message shouldBe
                GenreError.UnmappedStringNotFound(debugInfo = "c").message
        }

        test("should mark GenreError.UnmappedStringNotFound as not retryable") {
            GenreError.UnmappedStringNotFound().isRetryable shouldBe false
        }

        // ── GenreError.SlugConflict ───────────────────────────────────────────

        test("should round-trip GenreError.SlugConflict through AppError serializer") {
            val original: AppError =
                GenreError.SlugConflict(debugInfo = "slug=fantasy existing=g-fantasy")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should embed stable discriminator for GenreError.SlugConflict") {
            val json = contractJson.encodeToString(AppError.serializer(), GenreError.SlugConflict())
            json.contains("\"GenreError.SlugConflict\"") shouldBe true
        }

        test("should have constant body-level message for GenreError.SlugConflict") {
            GenreError.SlugConflict().message shouldBe
                GenreError.SlugConflict(debugInfo = "d").message
        }

        test("should mark GenreError.SlugConflict as not retryable") {
            GenreError.SlugConflict().isRetryable shouldBe false
        }
    })
