package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.api.error.AdminError
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BackupError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.CampfireError
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.error.ContributorError
import com.calypsan.listenup.api.error.CoverError
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.InviteError
import com.calypsan.listenup.api.error.LibraryError
import com.calypsan.listenup.api.error.LibraryWriteError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.error.MoodError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.error.ProfileError
import com.calypsan.listenup.api.error.PushError
import com.calypsan.listenup.api.error.ReadingOrderError
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.error.ServerConnectError
import com.calypsan.listenup.api.error.ShelfError
import com.calypsan.listenup.api.error.SocialError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.error.TagError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.io.MalformedMultipartException
import com.calypsan.listenup.server.io.MultipartPartTooLargeException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger("com.calypsan.listenup.server.plugins.AppErrorStatusPages")

/**
 * Surfaces unexpected throwables — genuine bugs, framework errors, OOM —
 * as a wire-shaped [InternalError] body with HTTP 500. Domain failures don't
 * get here: services return [AppResult.Failure] in-band, route handlers fold
 * them through [respondAppResult].
 *
 * Also handles 404s with a small JSON body so unknown paths don't return a
 * Ktor default page.
 */
fun Application.installAppErrorStatusPages() {
    install(StatusPages) {
        // A malformed multipart upload (truncated body, missing boundary) is a client error, not a
        // server fault — surface 400 rather than letting it fall through to a generic 500.
        exception<MalformedMultipartException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "bad_request", "reason" to "malformed_multipart"),
            )
        }
        // An upload past the configured size cap is 413 Payload Too Large, not a 500.
        exception<MultipartPartTooLargeException> { call, _ ->
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                mapOf("error" to "payload_too_large", "reason" to "multipart_part_too_large"),
            )
        }
        exception<Throwable> { call, ex ->
            // Cancellation must always re-raise — never swallow it.
            if (ex is CancellationException) throw ex
            // A client closing the connection mid-stream (audio seek/skip/pause/background) is
            // normal, not a server fault: log at DEBUG and don't dress it up as a 500 on an
            // already-committed response.
            if (isClientDisconnect(ex)) {
                logger.debug { "client disconnected mid-response on ${call.request.uri} — $ex" }
                return@exception
            }
            val correlationId = call.callId
            logger.error(ex) { "unhandled exception on ${call.request.uri} correlationId=$correlationId" }
            val body: AppError = InternalError(correlationId)
            call.respond(HttpStatusCode.InternalServerError, body)
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, mapOf("error" to "not_found", "path" to call.request.uri))
        }
    }
}

/**
 * Status mapping for typed [AppError]. Used by both REST handlers and tests.
 *
 * This `when` is exhaustive over all direct [AppError] implementors. Grouped branches keep its
 * cyclomatic complexity under the project threshold of 25 while preserving compile-time
 * exhaustiveness: the client-local [InternalError]/[TransportError]/[PlaybackError] share a 500
 * branch, [ShelfError]/[SocialError] delegate to [shelfOrSocialHttpStatus], and
 * [LibraryError]/[LibraryWriteError] delegate to [libraryFamilyHttpStatus], and
 * [BookError]/[CoverError] delegate to [bookOrCoverHttpStatus]. Adding a new
 * [AppError] sub-interface will still fail this `when` at compile time.
 */
internal fun AppError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is AuthError -> toHttpStatus()

        is DownloadError -> toHttpStatus()

        is ImportError -> toHttpStatus()

        is ScanError -> toHttpStatus()

        is ServerConnectError -> toHttpStatus()

        is SyncError -> toHttpStatus()

        is AudioMetadataError -> toHttpStatus()

        // LibraryError + LibraryWriteError share one branch (delegating to an exhaustive helper)
        // to keep this function's cyclomatic complexity under the project threshold while
        // preserving per-variant exhaustiveness for both families.
        is LibraryError, is LibraryWriteError -> libraryFamilyHttpStatus()

        is MetadataError -> toHttpStatus()

        // TagError + MoodError share one branch (delegating to an exhaustive helper) to keep
        // this function's cyclomatic complexity under the project threshold while preserving
        // per-variant exhaustiveness for both families.
        is TagError, is MoodError -> tagOrMoodHttpStatus()

        is CollectionError -> toHttpStatus()

        // ShelfError + SocialError + ReadingOrderError share one branch (delegating to an
        // exhaustive helper) to keep this function's cyclomatic complexity under the project
        // threshold while preserving per-variant exhaustiveness for all three families.
        is ShelfError, is SocialError, is ReadingOrderError -> shelfOrSocialHttpStatus()

        is AdminError -> toHttpStatus()

        is InviteError -> toHttpStatus()

        // BookError + CoverError share one branch (delegating to an exhaustive helper) to keep
        // this function's cyclomatic complexity under the project threshold while preserving
        // per-variant exhaustiveness for both families.
        is BookError, is CoverError -> bookOrCoverHttpStatus()

        is ContributorError -> toHttpStatus()

        is SeriesError -> toHttpStatus()

        is GenreError -> toHttpStatus()

        is ProfileError -> toHttpStatus()

        is BackupError -> toHttpStatus()

        is PushError -> toHttpStatus()

        is CampfireError -> toHttpStatus()

        is ValidationError -> HttpStatusCode.BadRequest

        // InternalError, TransportError, and PlaybackError are all server-bug / client-local paths;
        // grouped into a single branch so the function stays under the cyclomatic-complexity threshold
        // while remaining exhaustive — a new AppError subtype will still fail this when at compile time.
        is InternalError, is TransportError, is PlaybackError -> HttpStatusCode.InternalServerError
    }

/**
 * Stamps the request's correlation id onto a typed wire error.
 *
 * Exhaustive over all direct [AppError] implementors. The single-variant leaf families
 * ([ValidationError], [InternalError]) and the client-local types ([TransportError],
 * [PlaybackError]) are delegated to [leafWithCorrelationId], and [ShelfError]/[SocialError]
 * delegate to [shelfOrSocialWithCorrelationId], to keep the cyclomatic complexity of this
 * function under the project threshold of 25.
 */
internal fun AppError.withCorrelationId(id: String?): AppError =
    when (this) {
        is AuthError -> {
            withCorrelationId(id)
        }

        is DownloadError -> {
            withCorrelationId(id)
        }

        is ImportError -> {
            withCorrelationId(id)
        }

        is ScanError -> {
            withCorrelationId(id)
        }

        is ServerConnectError -> {
            withCorrelationId(id)
        }

        is SyncError -> {
            withCorrelationId(id)
        }

        is AudioMetadataError -> {
            withCorrelationId(id)
        }

        // LibraryError + LibraryWriteError share one branch (delegating to an exhaustive helper)
        // to keep this function's cyclomatic complexity under the project threshold while
        // preserving per-variant exhaustiveness for both families.
        is LibraryError, is LibraryWriteError -> {
            libraryFamilyWithCorrelationId(id)
        }

        is MetadataError -> {
            withCorrelationId(id)
        }

        // TagError + MoodError share one branch (delegating to an exhaustive helper) to keep
        // this function's cyclomatic complexity under the project threshold while preserving
        // per-variant exhaustiveness for both families.
        is TagError, is MoodError -> {
            tagOrMoodWithCorrelationId(id)
        }

        is CollectionError -> {
            withCorrelationId(id)
        }

        // Grouped for the same complexity-budget reason as [toHttpStatus]'s shelf/social branch;
        // the helper re-dispatches exhaustively per family.
        is ShelfError, is SocialError, is ReadingOrderError -> {
            shelfOrSocialWithCorrelationId(id)
        }

        is AdminError -> {
            withCorrelationId(id)
        }

        is InviteError -> {
            withCorrelationId(id)
        }

        is BookError -> {
            withCorrelationId(id)
        }

        is CoverError -> {
            withCorrelationId(id)
        }

        is ContributorError -> {
            withCorrelationId(id)
        }

        is SeriesError -> {
            withCorrelationId(id)
        }

        is GenreError -> {
            withCorrelationId(id)
        }

        is ProfileError -> {
            withCorrelationId(id)
        }

        is BackupError -> {
            withCorrelationId(id)
        }

        is PushError -> {
            withCorrelationId(id)
        }

        is CampfireError -> {
            withCorrelationId(id)
        }

        is ValidationError, is InternalError, is TransportError, is PlaybackError -> {
            leafWithCorrelationId(id)
        }
    }

/**
 * Stamps a correlation id onto the "leaf" error families that have no nested
 * `when` of their own: [ValidationError] and [InternalError] (server-produced,
 * single-variant `copy`) plus the client-local [TransportError] and [PlaybackError].
 *
 * Split from [withCorrelationId] solely to keep that function's cyclomatic complexity under the
 * project threshold. The `else` branch here is unreachable in practice — this function is only
 * called from the single grouped branch in [withCorrelationId].
 */
private fun AppError.leafWithCorrelationId(id: String?): AppError =
    when (this) {
        is ValidationError -> copy(correlationId = id)
        is InternalError -> copy(correlationId = id)
        is TransportError -> withCorrelationId(id)
        is PlaybackError -> withCorrelationId(id)
        else -> this // unreachable: only called from the grouped branch above
    }

/**
 * Status mapping for the library-folder error families, [LibraryError] and [LibraryWriteError].
 *
 * Split from [toHttpStatus] solely to keep that function's cyclomatic complexity under the
 * project threshold. The `else` branch here is unreachable in practice — this function is only
 * called from the single grouped branch in [toHttpStatus].
 */
private fun AppError.libraryFamilyHttpStatus(): HttpStatusCode =
    when (this) {
        is LibraryError -> toHttpStatus()
        is LibraryWriteError -> toHttpStatus()
        else -> HttpStatusCode.InternalServerError // unreachable: only called from the grouped branch
    }

/**
 * Correlation-id stamping for the library-folder error families, [LibraryError] and
 * [LibraryWriteError].
 *
 * Split from [withCorrelationId] solely to keep that function's cyclomatic complexity under the
 * project threshold. The `else` branch here is unreachable in practice — this function is only
 * called from the single grouped branch in [withCorrelationId].
 */
private fun AppError.libraryFamilyWithCorrelationId(id: String?): AppError =
    when (this) {
        is LibraryError -> withCorrelationId(id)
        is LibraryWriteError -> withCorrelationId(id)
        else -> this // unreachable: only called from the grouped branch above
    }

private fun AuthError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is AuthError.InvalidCredentials -> HttpStatusCode.Unauthorized
        is AuthError.EmailAlreadyExists -> HttpStatusCode.Conflict
        is AuthError.RegistrationDisabled -> HttpStatusCode.Forbidden
        is AuthError.SetupRequired -> HttpStatusCode.Conflict
        is AuthError.SetupAlreadyComplete -> HttpStatusCode.Conflict
        is AuthError.PendingApproval -> HttpStatusCode.Forbidden
        is AuthError.AccountDenied -> HttpStatusCode.Forbidden
        is AuthError.SessionExpired -> HttpStatusCode.Unauthorized
        is AuthError.ServerInstanceChanged -> HttpStatusCode.Unauthorized
        is AuthError.SessionNotFound -> HttpStatusCode.Unauthorized
        is AuthError.InvalidRefreshToken -> HttpStatusCode.Unauthorized
        is AuthError.RateLimited -> HttpStatusCode.TooManyRequests
        is AuthError.WeakPassword -> HttpStatusCode.BadRequest
        is AuthError.PermissionDenied -> HttpStatusCode.Forbidden
    }

private fun ScanError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is ScanError.AlreadyRunning -> HttpStatusCode.Conflict
        is ScanError.LibraryPathNotConfigured -> HttpStatusCode.ServiceUnavailable
        is ScanError.LibraryPathNotFound -> HttpStatusCode.ServiceUnavailable
        is ScanError.FileUnreadable -> HttpStatusCode.InternalServerError
        is ScanError.MetadataParseError -> HttpStatusCode.InternalServerError
        is ScanError.TitleInferenceError -> HttpStatusCode.InternalServerError
    }

private fun AuthError.withCorrelationId(id: String?): AuthError =
    when (this) {
        is AuthError.InvalidCredentials -> copy(correlationId = id)
        is AuthError.EmailAlreadyExists -> copy(correlationId = id)
        is AuthError.RegistrationDisabled -> copy(correlationId = id)
        is AuthError.SetupRequired -> copy(correlationId = id)
        is AuthError.SetupAlreadyComplete -> copy(correlationId = id)
        is AuthError.PendingApproval -> copy(correlationId = id)
        is AuthError.AccountDenied -> copy(correlationId = id)
        is AuthError.SessionExpired -> copy(correlationId = id)
        is AuthError.ServerInstanceChanged -> copy(correlationId = id)
        is AuthError.SessionNotFound -> copy(correlationId = id)
        is AuthError.InvalidRefreshToken -> copy(correlationId = id)
        is AuthError.RateLimited -> copy(correlationId = id)
        is AuthError.WeakPassword -> copy(correlationId = id)
        is AuthError.PermissionDenied -> copy(correlationId = id)
    }

private fun ScanError.withCorrelationId(id: String?): ScanError =
    when (this) {
        is ScanError.AlreadyRunning -> copy(correlationId = id)
        is ScanError.LibraryPathNotConfigured -> copy(correlationId = id)
        is ScanError.LibraryPathNotFound -> copy(correlationId = id)
        is ScanError.FileUnreadable -> copy(correlationId = id)
        is ScanError.MetadataParseError -> copy(correlationId = id)
        is ScanError.TitleInferenceError -> copy(correlationId = id)
    }

private fun LibraryWriteError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is LibraryWriteError.Unavailable -> HttpStatusCode.ServiceUnavailable
    }

private fun LibraryWriteError.withCorrelationId(id: String?): LibraryWriteError =
    when (this) {
        is LibraryWriteError.Unavailable -> copy(correlationId = id)
    }

private fun TransportError.withCorrelationId(id: String?): TransportError =
    when (this) {
        is TransportError.NetworkUnavailable -> copy(correlationId = id)
        is TransportError.Timeout -> copy(correlationId = id)
        is TransportError.Server4xx -> copy(correlationId = id)
        is TransportError.Server5xx -> copy(correlationId = id)
        is TransportError.DataMalformed -> copy(correlationId = id)
    }

private fun SyncError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is SyncError.SyncFailed -> HttpStatusCode.ServiceUnavailable
        is SyncError.RealtimeDisconnected -> HttpStatusCode.ServiceUnavailable
        is SyncError.PushFailed -> HttpStatusCode.ServiceUnavailable
        is SyncError.NotFound -> HttpStatusCode.NotFound
    }

private fun SyncError.withCorrelationId(id: String?): SyncError =
    when (this) {
        is SyncError.SyncFailed -> copy(correlationId = id)
        is SyncError.RealtimeDisconnected -> copy(correlationId = id)
        is SyncError.PushFailed -> copy(correlationId = id)
        is SyncError.NotFound -> copy(correlationId = id)
    }

private fun DownloadError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is DownloadError.DownloadFailed -> HttpStatusCode.ServiceUnavailable
        is DownloadError.InsufficientStorage -> HttpStatusCode.InsufficientStorage
    }

private fun DownloadError.withCorrelationId(id: String?): DownloadError =
    when (this) {
        is DownloadError.DownloadFailed -> copy(correlationId = id)
        is DownloadError.InsufficientStorage -> copy(correlationId = id)
    }

private fun ImportError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is ImportError.UploadFailed -> HttpStatusCode.UnprocessableEntity
        is ImportError.AnalysisFailed -> HttpStatusCode.ServiceUnavailable
        is ImportError.ApplyFailed -> HttpStatusCode.ServiceUnavailable
        is ImportError.ImportNotFound -> HttpStatusCode.NotFound
        is ImportError.MappingInvalid -> HttpStatusCode.BadRequest
    }

private fun ImportError.withCorrelationId(id: String?): ImportError =
    when (this) {
        is ImportError.UploadFailed -> copy(correlationId = id)
        is ImportError.AnalysisFailed -> copy(correlationId = id)
        is ImportError.ApplyFailed -> copy(correlationId = id)
        is ImportError.ImportNotFound -> copy(correlationId = id)
        is ImportError.MappingInvalid -> copy(correlationId = id)
    }

private fun ServerConnectError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is ServerConnectError.InvalidUrl -> HttpStatusCode.BadRequest

        is ServerConnectError.NotListenUpServer -> HttpStatusCode.BadGateway

        is ServerConnectError.ServerNotReachable -> HttpStatusCode.ServiceUnavailable

        is ServerConnectError.VerificationFailed -> HttpStatusCode.ServiceUnavailable

        // Client-local: the wire never carries this variant. Branch exists for
        // exhaustiveness only; if it ever reaches the server, treat it as a
        // malformed client request.
        is ServerConnectError.LocalNetworkPermissionDenied -> HttpStatusCode.BadRequest
    }

private fun ServerConnectError.withCorrelationId(id: String?): ServerConnectError =
    when (this) {
        is ServerConnectError.InvalidUrl -> copy(correlationId = id)
        is ServerConnectError.NotListenUpServer -> copy(correlationId = id)
        is ServerConnectError.ServerNotReachable -> copy(correlationId = id)
        is ServerConnectError.VerificationFailed -> copy(correlationId = id)
        is ServerConnectError.LocalNetworkPermissionDenied -> copy(correlationId = id)
    }

private fun AudioMetadataError.toHttpStatus(): HttpStatusCode =
    when (this) {
        // The server can't parse this format. 415 communicates "the server understands
        // the request but won't process media of this type."
        is AudioMetadataError.UnsupportedFormat -> HttpStatusCode.UnsupportedMediaType

        // Format detected, content malformed. 422 — request was well-formed but the
        // entity it referenced is semantically invalid.
        is AudioMetadataError.CorruptHeader -> HttpStatusCode.UnprocessableEntity

        is AudioMetadataError.TruncatedStream -> HttpStatusCode.UnprocessableEntity

        // Server-side IO failure (permission denied, transient disk error). 500.
        is AudioMetadataError.IoError -> HttpStatusCode.InternalServerError
    }

private fun AudioMetadataError.withCorrelationId(id: String?): AudioMetadataError =
    when (this) {
        is AudioMetadataError.UnsupportedFormat -> copy(correlationId = id)
        is AudioMetadataError.CorruptHeader -> copy(correlationId = id)
        is AudioMetadataError.TruncatedStream -> copy(correlationId = id)
        is AudioMetadataError.IoError -> copy(correlationId = id)
    }

private fun PlaybackError.withCorrelationId(id: String?): PlaybackError =
    when (this) {
        is PlaybackError.Stalled -> copy(correlationId = id)
    }

private fun MetadataError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is MetadataError.ExternalRateLimited -> HttpStatusCode.TooManyRequests
        is MetadataError.ExternalUnavailable -> HttpStatusCode.ServiceUnavailable
        is MetadataError.NotFound -> HttpStatusCode.NotFound
        is MetadataError.Malformed -> HttpStatusCode.BadGateway
        is MetadataError.ChapterCountMismatch -> HttpStatusCode.UnprocessableEntity
    }

private fun MetadataError.withCorrelationId(id: String?): MetadataError =
    when (this) {
        is MetadataError.ExternalRateLimited -> copy(correlationId = id)
        is MetadataError.ExternalUnavailable -> copy(correlationId = id)
        is MetadataError.NotFound -> copy(correlationId = id)
        is MetadataError.Malformed -> copy(correlationId = id)
        is MetadataError.ChapterCountMismatch -> copy(correlationId = id)
    }

private fun LibraryError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is LibraryError.NotFound -> HttpStatusCode.NotFound
        is LibraryError.InvalidPath -> HttpStatusCode.BadRequest
        is LibraryError.DuplicateFolder -> HttpStatusCode.Conflict
        is LibraryError.FolderNotFound -> HttpStatusCode.NotFound
    }

private fun LibraryError.withCorrelationId(id: String?): LibraryError =
    when (this) {
        is LibraryError.NotFound -> copy(correlationId = id)
        is LibraryError.InvalidPath -> copy(correlationId = id)
        is LibraryError.DuplicateFolder -> copy(correlationId = id)
        is LibraryError.FolderNotFound -> copy(correlationId = id)
    }

/**
 * Re-dispatches the grouped `TagError`/`MoodError` branch of [toHttpStatus] to each family's
 * own exhaustive mapping. Split out solely to keep [toHttpStatus]'s cyclomatic complexity under
 * the project threshold; the `else` is unreachable (only called from the grouped branch above).
 */
private fun AppError.tagOrMoodHttpStatus(): HttpStatusCode =
    when (this) {
        is TagError -> toHttpStatus()
        is MoodError -> toHttpStatus()
        else -> HttpStatusCode.InternalServerError // unreachable: only called from the grouped branch
    }

/**
 * Re-dispatches the grouped `TagError`/`MoodError` branch of [withCorrelationId] to each family's
 * own exhaustive `copy`. Split out solely to keep [withCorrelationId]'s cyclomatic complexity under
 * the project threshold; the `else` is unreachable (only called from the grouped branch above).
 */
private fun AppError.tagOrMoodWithCorrelationId(id: String?): AppError =
    when (this) {
        is TagError -> withCorrelationId(id)
        is MoodError -> withCorrelationId(id)
        else -> this // unreachable: only called from the grouped branch above
    }

private fun TagError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is TagError.NotFound -> HttpStatusCode.NotFound
        is TagError.BookNotFound -> HttpStatusCode.NotFound
        is TagError.InvalidName -> HttpStatusCode.BadRequest
        is TagError.NameTooLong -> HttpStatusCode.BadRequest
    }

private fun TagError.withCorrelationId(id: String?): TagError =
    when (this) {
        is TagError.NotFound -> copy(correlationId = id)
        is TagError.BookNotFound -> copy(correlationId = id)
        is TagError.InvalidName -> copy(correlationId = id)
        is TagError.NameTooLong -> copy(correlationId = id)
    }

private fun MoodError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is MoodError.NotFound -> HttpStatusCode.NotFound
        is MoodError.BookNotFound -> HttpStatusCode.NotFound
        is MoodError.InvalidName -> HttpStatusCode.BadRequest
        is MoodError.NameTooLong -> HttpStatusCode.BadRequest
    }

private fun MoodError.withCorrelationId(id: String?): MoodError =
    when (this) {
        is MoodError.NotFound -> copy(correlationId = id)
        is MoodError.BookNotFound -> copy(correlationId = id)
        is MoodError.InvalidName -> copy(correlationId = id)
        is MoodError.NameTooLong -> copy(correlationId = id)
    }

/**
 * Re-dispatches the grouped `BookError`/`CoverError` branch of [toHttpStatus] to each family's
 * own exhaustive mapping. Split out solely to keep [toHttpStatus]'s cyclomatic complexity under
 * the project threshold; the `else` is unreachable (only called from the grouped branch above).
 */
private fun AppError.bookOrCoverHttpStatus(): HttpStatusCode =
    when (this) {
        is BookError -> toHttpStatus()
        is CoverError -> toHttpStatus()
        else -> HttpStatusCode.InternalServerError // unreachable: only called from the grouped branch
    }

private fun BookError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is BookError.NotFound -> HttpStatusCode.NotFound
        is BookError.InvalidInput -> HttpStatusCode.BadRequest
    }

private fun BookError.withCorrelationId(id: String?): BookError =
    when (this) {
        is BookError.NotFound -> copy(correlationId = id)
        is BookError.InvalidInput -> copy(correlationId = id)
    }

private fun CoverError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is CoverError.NotPresent -> HttpStatusCode.NotFound
    }

private fun CoverError.withCorrelationId(id: String?): CoverError =
    when (this) {
        is CoverError.NotPresent -> copy(correlationId = id)
    }

private fun ContributorError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is ContributorError.NotFound -> HttpStatusCode.NotFound
        is ContributorError.InvalidInput -> HttpStatusCode.BadRequest
        is ContributorError.MergeSelfTarget -> HttpStatusCode.BadRequest
        is ContributorError.AliasNotFound -> HttpStatusCode.NotFound
    }

private fun ContributorError.withCorrelationId(id: String?): ContributorError =
    when (this) {
        is ContributorError.NotFound -> copy(correlationId = id)
        is ContributorError.InvalidInput -> copy(correlationId = id)
        is ContributorError.MergeSelfTarget -> copy(correlationId = id)
        is ContributorError.AliasNotFound -> copy(correlationId = id)
    }

private fun SeriesError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is SeriesError.NotFound -> HttpStatusCode.NotFound
        is SeriesError.InvalidInput -> HttpStatusCode.BadRequest
        is SeriesError.MergeSelfTarget -> HttpStatusCode.BadRequest
    }

private fun SeriesError.withCorrelationId(id: String?): SeriesError =
    when (this) {
        is SeriesError.NotFound -> copy(correlationId = id)
        is SeriesError.InvalidInput -> copy(correlationId = id)
        is SeriesError.MergeSelfTarget -> copy(correlationId = id)
    }

private fun GenreError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is GenreError.NotFound -> HttpStatusCode.NotFound
        is GenreError.UnmappedStringNotFound -> HttpStatusCode.NotFound
        is GenreError.InvalidInput -> HttpStatusCode.BadRequest
        is GenreError.MergeSelfTarget -> HttpStatusCode.BadRequest
        is GenreError.MoveSelfDescendant -> HttpStatusCode.BadRequest
        is GenreError.HasDescendants -> HttpStatusCode.Conflict
        is GenreError.SlugConflict -> HttpStatusCode.Conflict
    }

private fun GenreError.withCorrelationId(id: String?): GenreError =
    when (this) {
        is GenreError.NotFound -> copy(correlationId = id)
        is GenreError.UnmappedStringNotFound -> copy(correlationId = id)
        is GenreError.InvalidInput -> copy(correlationId = id)
        is GenreError.MergeSelfTarget -> copy(correlationId = id)
        is GenreError.MoveSelfDescendant -> copy(correlationId = id)
        is GenreError.HasDescendants -> copy(correlationId = id)
        is GenreError.SlugConflict -> copy(correlationId = id)
    }

private fun CollectionError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is CollectionError.NotFound -> HttpStatusCode.NotFound
        is CollectionError.BookNotFound -> HttpStatusCode.NotFound
        is CollectionError.UserNotFound -> HttpStatusCode.NotFound
        is CollectionError.Forbidden -> HttpStatusCode.Forbidden
        is CollectionError.InvalidInput -> HttpStatusCode.BadRequest
        is CollectionError.SystemCollectionReadOnly -> HttpStatusCode.BadRequest
        is CollectionError.SelfShare -> HttpStatusCode.BadRequest
        is CollectionError.AlreadyShared -> HttpStatusCode.BadRequest
    }

private fun CollectionError.withCorrelationId(id: String?): CollectionError =
    when (this) {
        is CollectionError.NotFound -> copy(correlationId = id)
        is CollectionError.BookNotFound -> copy(correlationId = id)
        is CollectionError.UserNotFound -> copy(correlationId = id)
        is CollectionError.Forbidden -> copy(correlationId = id)
        is CollectionError.InvalidInput -> copy(correlationId = id)
        is CollectionError.SystemCollectionReadOnly -> copy(correlationId = id)
        is CollectionError.SelfShare -> copy(correlationId = id)
        is CollectionError.AlreadyShared -> copy(correlationId = id)
    }

private fun ShelfError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is ShelfError.NotFound -> HttpStatusCode.NotFound
        is ShelfError.Forbidden -> HttpStatusCode.Forbidden
        is ShelfError.InvalidName -> HttpStatusCode.BadRequest
    }

private fun ShelfError.withCorrelationId(id: String?): ShelfError =
    when (this) {
        is ShelfError.NotFound -> copy(correlationId = id)
        is ShelfError.Forbidden -> copy(correlationId = id)
        is ShelfError.InvalidName -> copy(correlationId = id)
    }

/**
 * Re-dispatches the grouped `ShelfError`/`SocialError`/`ReadingOrderError` branch of [toHttpStatus] to each family's
 * own exhaustive mapping. Split out solely to keep [toHttpStatus]'s cyclomatic complexity under the
 * project threshold; the `else` is unreachable (only called from the grouped branch above).
 */
private fun AppError.shelfOrSocialHttpStatus(): HttpStatusCode =
    when (this) {
        is ShelfError -> toHttpStatus()
        is SocialError -> toHttpStatus()
        is ReadingOrderError -> toHttpStatus()
        else -> HttpStatusCode.InternalServerError // unreachable: only called from the grouped branch
    }

/**
 * Re-dispatches the grouped `ShelfError`/`SocialError`/`ReadingOrderError` branch of
 * [withCorrelationId] to each family's own exhaustive mapping. Split out solely to keep
 * [withCorrelationId]'s cyclomatic complexity under the project threshold; the `else` is
 * unreachable (only called from the grouped branch above).
 */
private fun AppError.shelfOrSocialWithCorrelationId(id: String?): AppError =
    when (this) {
        is ShelfError -> withCorrelationId(id)
        is SocialError -> withCorrelationId(id)
        is ReadingOrderError -> withCorrelationId(id)
        else -> this // unreachable: only called from the grouped branch
    }

private fun ReadingOrderError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is ReadingOrderError.NotFound -> HttpStatusCode.NotFound
        is ReadingOrderError.Forbidden -> HttpStatusCode.Forbidden
        is ReadingOrderError.InvalidName -> HttpStatusCode.BadRequest
    }

private fun ReadingOrderError.withCorrelationId(id: String?): ReadingOrderError =
    when (this) {
        is ReadingOrderError.NotFound -> copy(correlationId = id)
        is ReadingOrderError.Forbidden -> copy(correlationId = id)
        is ReadingOrderError.InvalidName -> copy(correlationId = id)
    }

private fun SocialError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is SocialError.NotFound -> HttpStatusCode.NotFound
    }

private fun SocialError.withCorrelationId(id: String?): SocialError =
    when (this) {
        is SocialError.NotFound -> copy(correlationId = id)
    }

private fun AdminError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is AdminError.UserNotFound -> HttpStatusCode.NotFound
        is AdminError.CannotModifyRoot -> HttpStatusCode.Conflict
        is AdminError.CannotDemoteLastAdmin -> HttpStatusCode.Conflict
        is AdminError.CannotDeleteSelf -> HttpStatusCode.Conflict
        is AdminError.CannotDeleteLastAdmin -> HttpStatusCode.Conflict
        is AdminError.InvalidInput -> HttpStatusCode.BadRequest
    }

private fun AdminError.withCorrelationId(id: String?): AdminError =
    when (this) {
        is AdminError.UserNotFound -> copy(correlationId = id)
        is AdminError.CannotModifyRoot -> copy(correlationId = id)
        is AdminError.CannotDemoteLastAdmin -> copy(correlationId = id)
        is AdminError.CannotDeleteSelf -> copy(correlationId = id)
        is AdminError.CannotDeleteLastAdmin -> copy(correlationId = id)
        is AdminError.InvalidInput -> copy(correlationId = id)
    }

private fun InviteError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is InviteError.NotFound -> HttpStatusCode.NotFound
        is InviteError.Expired -> HttpStatusCode.Conflict
        is InviteError.AlreadyClaimed -> HttpStatusCode.Conflict
        is InviteError.EmailInUse -> HttpStatusCode.Conflict
        is InviteError.InvalidInput -> HttpStatusCode.BadRequest
    }

private fun InviteError.withCorrelationId(id: String?): InviteError =
    when (this) {
        is InviteError.NotFound -> copy(correlationId = id)
        is InviteError.Expired -> copy(correlationId = id)
        is InviteError.AlreadyClaimed -> copy(correlationId = id)
        is InviteError.EmailInUse -> copy(correlationId = id)
        is InviteError.InvalidInput -> copy(correlationId = id)
    }

private fun ProfileError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is ProfileError.InvalidImage -> HttpStatusCode.UnprocessableEntity
        is ProfileError.WrongPassword -> HttpStatusCode.UnprocessableEntity
    }

private fun ProfileError.withCorrelationId(id: String?): ProfileError =
    when (this) {
        is ProfileError.InvalidImage -> copy(correlationId = id)
        is ProfileError.WrongPassword -> copy(correlationId = id)
    }

private fun BackupError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is BackupError.SnapshotFailed -> HttpStatusCode.InternalServerError
        is BackupError.CorruptArchive -> HttpStatusCode.UnprocessableEntity
        is BackupError.IncompatibleSchema -> HttpStatusCode.Conflict
        is BackupError.BackupNotFound -> HttpStatusCode.NotFound
        is BackupError.RestoreInProgress -> HttpStatusCode.ServiceUnavailable
        is BackupError.RestoreFailed -> HttpStatusCode.InternalServerError
    }

private fun BackupError.withCorrelationId(id: String?): BackupError =
    when (this) {
        is BackupError.SnapshotFailed -> copy(correlationId = id)
        is BackupError.CorruptArchive -> copy(correlationId = id)
        is BackupError.IncompatibleSchema -> copy(correlationId = id)
        is BackupError.BackupNotFound -> copy(correlationId = id)
        is BackupError.RestoreInProgress -> copy(correlationId = id)
        is BackupError.RestoreFailed -> copy(correlationId = id)
    }

private fun PushError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is PushError.PushDisabled -> HttpStatusCode.ServiceUnavailable
    }

private fun PushError.withCorrelationId(id: String?): PushError =
    when (this) {
        is PushError.PushDisabled -> copy(correlationId = id)
    }

private fun CampfireError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is CampfireError.CampfireNotFound -> HttpStatusCode.NotFound

        is CampfireError.CampfireFull -> HttpStatusCode.Conflict

        is CampfireError.NotAMember -> HttpStatusCode.Forbidden

        is CampfireError.NotController -> HttpStatusCode.Forbidden

        is CampfireError.BookAccessDenied -> HttpStatusCode.Forbidden

        // The room exists but is in the wrong lifecycle phase for the requested action —
        // a conflict with current room state, not a missing-resource or permission failure.
        is CampfireError.NotStarted -> HttpStatusCode.Conflict
    }

private fun CampfireError.withCorrelationId(id: String?): CampfireError =
    when (this) {
        is CampfireError.CampfireNotFound -> copy(correlationId = id)
        is CampfireError.CampfireFull -> copy(correlationId = id)
        is CampfireError.NotAMember -> copy(correlationId = id)
        is CampfireError.NotController -> copy(correlationId = id)
        is CampfireError.BookAccessDenied -> copy(correlationId = id)
        is CampfireError.NotStarted -> copy(correlationId = id)
    }
