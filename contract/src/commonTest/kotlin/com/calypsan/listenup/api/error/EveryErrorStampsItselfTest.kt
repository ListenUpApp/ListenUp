package com.calypsan.listenup.api.error

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Stamping a correlation id must change the correlation id and nothing else. Kotlin common has no
 * reflection, so the population is one hand-picked instance per error family, preferring a variant
 * with extra constructor fields — the fields a careless `copy` would drop.
 */
class EveryErrorStampsItselfTest :
    FunSpec({
        val onePerFamily: List<AppError> =
            listOf(
                AdminError.CannotModifyRoot(debugInfo = "admin"),
                AudioMetadataError.UnsupportedFormat(pathString = "/a.xyz", detectedMagic = "FOO"),
                AuthError.ResetCodeIncorrect(attemptsRemaining = 3),
                BackupError.SnapshotFailed(debugInfo = "backup"),
                BookError.NotFound(debugInfo = "book"),
                CollectionError.NotFound(debugInfo = "collection"),
                ContributorError.NotFound(debugInfo = "contributor"),
                CoverError.NotPresent(debugInfo = "cover"),
                DownloadError.DownloadFailed(bookTitle = "Dune"),
                EntityError.NotFound(debugInfo = "entity"),
                GenreError.NotFound(debugInfo = "genre"),
                HardcoverError.NotConfigured(debugInfo = "hardcover"),
                ImportError.UploadFailed(debugInfo = "import"),
                InviteError.NotFound(debugInfo = "invite"),
                LibraryError.NotFound(debugInfo = "library"),
                LibraryWriteError.Unavailable(debugInfo = "library-write"),
                MetadataError.ExternalRateLimited(retryAfterSeconds = 30),
                MoodError.NotFound(debugInfo = "mood"),
                PlaybackError.Stalled(debugInfo = "playback"),
                ProfileError.InvalidImage(debugInfo = "profile"),
                PushError.PushDisabled(debugInfo = "push"),
                RatingError.SourceUnavailable(debugInfo = "rating"),
                ReadingOrderError.NotFound(debugInfo = "reading-order"),
                ScanError.AlreadyRunning(debugInfo = "scan"),
                SeriesError.NotFound(debugInfo = "series"),
                ServerConnectError.InvalidUrl(reason = "no scheme"),
                ShelfError.NotFound(debugInfo = "shelf"),
                SocialError.NotFound(debugInfo = "social"),
                SyncError.SyncFailed(debugInfo = "sync"),
                TagError.NotFound(debugInfo = "tag"),
                TranscodeError.TranscoderBusy(debugInfo = "transcode"),
                TransportError.NetworkUnavailable(debugInfo = "transport"),
                UploadError.SessionNotFound(debugInfo = "upload"),
                WorldEventError.NotFound(debugInfo = "world-event"),
                InternalError(debugInfo = "internal", cause = "NullPointerException"),
                UnexpectedClientError(debugInfo = "client"),
                UnknownError(code = "FUTURE_CODE", message = "From a newer server.", isRetryable = true),
                ValidationError(message = "Too short.", field = "password"),
            )

        test("the population covers every family and every direct implementor") {
            onePerFamily.size shouldBe 38
        }

        onePerFamily.forEach { error ->
            test("${error.code} stamps its correlation id and nothing else") {
                val stamped = error.withCorrelationId("corr-1")

                stamped.correlationId shouldBe "corr-1"
                stamped::class shouldBe error::class
                stamped.code shouldBe error.code
                stamped.withCorrelationId(null) shouldBe error
            }
        }
    })
