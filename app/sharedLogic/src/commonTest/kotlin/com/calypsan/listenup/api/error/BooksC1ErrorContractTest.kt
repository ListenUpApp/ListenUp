package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the round-trip behaviour, stable `@SerialName` discriminators, and body-level
 * message constants for all Books-C1 error subtypes: [BookError], [ContributorError],
 * [SeriesError], and [CoverError]. Encoding through [AppError.serializer] exercises
 * the polymorphic discriminator path.
 */
class BooksC1ErrorContractTest :
    FunSpec({

        // ── BookError ─────────────────────────────────────────────────────────

        test("should round-trip BookError.NotFound through AppError serializer") {
            val original: AppError = BookError.NotFound(correlationId = "abc", debugInfo = "bookId=book1")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            val decoded = contractJson.decodeFromString(AppError.serializer(), json)
            decoded shouldBe original
        }

        test("should embed stable discriminator for BookError.NotFound") {
            val json = contractJson.encodeToString(AppError.serializer(), BookError.NotFound())
            json.contains("\"BookError.NotFound\"") shouldBe true
        }

        test("should round-trip BookError.InvalidInput through AppError serializer") {
            val original: AppError = BookError.InvalidInput(debugInfo = "title: must be 1..500 chars")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should have constant body-level message for BookError subtypes") {
            BookError.NotFound().message shouldBe BookError.NotFound(debugInfo = "x").message
            BookError.InvalidInput().message shouldBe BookError.InvalidInput(debugInfo = "x").message
        }

        test("should mark BookError subtypes as not retryable") {
            BookError.NotFound().isRetryable shouldBe false
            BookError.InvalidInput().isRetryable shouldBe false
            BookError.FolderNotExclusive(otherBookId = "b2", otherBookTitle = "Animal Farm").isRetryable shouldBe false
        }

        // ── Delete Book refusals ──────────────────────────────────────────────

        test("should round-trip BookError.FolderNotExclusive, blocking book and all, through AppError serializer") {
            val original: AppError =
                BookError.FolderNotExclusive(
                    otherBookId = "b2",
                    otherBookTitle = "Animal Farm",
                    correlationId = "req-9",
                    debugInfo = "book b1 at /library/George Orwell also holds book b2",
                )
            val json = contractJson.encodeToString(AppError.serializer(), original)
            val decoded = contractJson.decodeFromString(AppError.serializer(), json)
            // Naming the blocking book is the whole point of this subtype, and the constant
            // body-level `message` cannot carry it — so it has to survive the wire on its own fields.
            decoded shouldBe original
            (decoded as BookError.FolderNotExclusive).otherBookTitle shouldBe "Animal Farm"
        }

        test("should embed stable discriminator for BookError.FolderNotExclusive") {
            val json =
                contractJson.encodeToString(
                    AppError.serializer(),
                    BookError.FolderNotExclusive(otherBookId = "b2", otherBookTitle = "Animal Farm"),
                )
            json.contains("\"BookError.FolderNotExclusive\"") shouldBe true
        }

        test("should keep BookError.FolderNotExclusive's body-level message constant across instances") {
            BookError.FolderNotExclusive(otherBookId = "b2", otherBookTitle = "x").message shouldBe
                BookError.FolderNotExclusive(otherBookId = "b3", otherBookTitle = "y").message
        }

        test("should round-trip LibraryWriteError.ProtectedPath through AppError serializer") {
            val original: AppError = LibraryWriteError.ProtectedPath(debugInfo = "/library is a library folder root")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should embed stable discriminator for LibraryWriteError.ProtectedPath") {
            val json = contractJson.encodeToString(AppError.serializer(), LibraryWriteError.ProtectedPath())
            json.contains("\"LibraryWriteError.ProtectedPath\"") shouldBe true
        }

        test("should mark LibraryWriteError.ProtectedPath not retryable — re-firing it cannot change the answer") {
            LibraryWriteError.ProtectedPath().isRetryable shouldBe false
            LibraryWriteError.ProtectedPath().message shouldBe LibraryWriteError.ProtectedPath(debugInfo = "x").message
        }

        // ── ContributorError ──────────────────────────────────────────────────

        test("should round-trip ContributorError.NotFound through AppError serializer") {
            val original: AppError = ContributorError.NotFound(debugInfo = "contributorId=c1")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should embed stable discriminator for ContributorError.NotFound") {
            val json = contractJson.encodeToString(AppError.serializer(), ContributorError.NotFound())
            json.contains("\"ContributorError.NotFound\"") shouldBe true
        }

        test("should round-trip ContributorError.InvalidInput through AppError serializer") {
            val original: AppError = ContributorError.InvalidInput(correlationId = "req-1")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should have constant body-level message for ContributorError subtypes") {
            ContributorError.NotFound().message shouldBe ContributorError.NotFound(debugInfo = "y").message
            ContributorError.InvalidInput().message shouldBe ContributorError.InvalidInput(debugInfo = "y").message
        }

        // ── SeriesError ───────────────────────────────────────────────────────

        test("should round-trip SeriesError.NotFound through AppError serializer") {
            val original: AppError = SeriesError.NotFound(debugInfo = "seriesId=s1")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should embed stable discriminator for SeriesError.NotFound") {
            val json = contractJson.encodeToString(AppError.serializer(), SeriesError.NotFound())
            json.contains("\"SeriesError.NotFound\"") shouldBe true
        }

        test("should round-trip SeriesError.InvalidInput through AppError serializer") {
            val original: AppError = SeriesError.InvalidInput(correlationId = "req-2")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should have constant body-level message for SeriesError subtypes") {
            SeriesError.NotFound().message shouldBe SeriesError.NotFound(debugInfo = "z").message
            SeriesError.InvalidInput().message shouldBe SeriesError.InvalidInput(debugInfo = "z").message
        }

        // ── CoverError ────────────────────────────────────────────────────────

        test("should round-trip CoverError.NotPresent through AppError serializer") {
            val original: AppError = CoverError.NotPresent(debugInfo = "bookId=book1")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should embed stable discriminator for CoverError.NotPresent") {
            val json = contractJson.encodeToString(AppError.serializer(), CoverError.NotPresent())
            json.contains("\"CoverError.NotPresent\"") shouldBe true
        }

        test("should have constant body-level message for CoverError.NotPresent") {
            CoverError.NotPresent().message shouldBe CoverError.NotPresent(debugInfo = "w").message
        }
    })
