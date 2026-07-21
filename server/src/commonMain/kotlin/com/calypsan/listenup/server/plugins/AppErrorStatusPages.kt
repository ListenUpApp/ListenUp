package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.api.error.AdminError
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BackupError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.error.ContributorError
import com.calypsan.listenup.api.error.CoverError
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.InviteError
import com.calypsan.listenup.api.error.LibraryError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.error.MoodError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.error.ProfileError
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.error.ServerConnectError
import com.calypsan.listenup.api.error.ShelfError
import com.calypsan.listenup.api.error.SocialError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.error.TagError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.error.withCorrelationId as stampCorrelationId
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
 * Server-side alias for the canonical [com.calypsan.listenup.api.error.withCorrelationId] stamp,
 * which lives in `:contract` (co-located with every [AppError] subtype) so the REST fold and the
 * RPC guard share one exhaustive implementation. Kept here under the `plugins` package so the many
 * route folds that `import ...plugins.withCorrelationId` need no churn; it forwards verbatim.
 */
internal fun AppError.withCorrelationId(id: String?): AppError = stampCorrelationId(id)

/**
 * Status mapping for typed [AppError]. Used by both REST handlers and tests.
 *
 * This `when` is exhaustive over all direct [AppError] implementors. Two grouped branches keep its
 * cyclomatic complexity under the project threshold of 25 while preserving compile-time
 * exhaustiveness: the client-local [InternalError]/[TransportError]/[PlaybackError] share a 500
 * branch, and [ShelfError]/[SocialError] delegate to [shelfOrSocialHttpStatus]. Adding a new
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

        is LibraryError -> toHttpStatus()

        is MetadataError -> toHttpStatus()

        // TagError + MoodError share one branch (delegating to an exhaustive helper) to keep
        // this function's cyclomatic complexity under the project threshold while preserving
        // per-variant exhaustiveness for both families.
        is TagError, is MoodError -> tagOrMoodHttpStatus()

        is CollectionError -> toHttpStatus()

        // ShelfError + SocialError share one branch (delegating to an exhaustive helper) to keep
        // this function's cyclomatic complexity under the project threshold while preserving
        // per-variant exhaustiveness for both families.
        is ShelfError, is SocialError -> shelfOrSocialHttpStatus()

        is AdminError -> toHttpStatus()

        is InviteError -> toHttpStatus()

        is BookError -> toHttpStatus()

        is CoverError -> toHttpStatus()

        is ContributorError -> toHttpStatus()

        is SeriesError -> toHttpStatus()

        is GenreError -> toHttpStatus()

        is ProfileError -> toHttpStatus()

        is BackupError -> toHttpStatus()

        is ValidationError -> HttpStatusCode.BadRequest

        // InternalError, TransportError, and PlaybackError are all server-bug / client-local paths;
        // grouped into a single branch so the function stays under the cyclomatic-complexity threshold
        // while remaining exhaustive — a new AppError subtype will still fail this when at compile time.
        is InternalError, is TransportError, is PlaybackError -> HttpStatusCode.InternalServerError
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
        is AuthError.RegistrationNotFound -> HttpStatusCode.NotFound
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

private fun SyncError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is SyncError.SyncFailed -> HttpStatusCode.ServiceUnavailable
        is SyncError.RealtimeDisconnected -> HttpStatusCode.ServiceUnavailable
        is SyncError.PushFailed -> HttpStatusCode.ServiceUnavailable
        is SyncError.NotFound -> HttpStatusCode.NotFound
    }

private fun DownloadError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is DownloadError.DownloadFailed -> HttpStatusCode.ServiceUnavailable
        is DownloadError.InsufficientStorage -> HttpStatusCode.InsufficientStorage
    }

private fun ImportError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is ImportError.UploadFailed -> HttpStatusCode.UnprocessableEntity
        is ImportError.AnalysisFailed -> HttpStatusCode.ServiceUnavailable
        is ImportError.ApplyFailed -> HttpStatusCode.ServiceUnavailable
        is ImportError.ImportNotFound -> HttpStatusCode.NotFound
        is ImportError.MappingInvalid -> HttpStatusCode.BadRequest
    }

private fun ServerConnectError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is ServerConnectError.InvalidUrl -> HttpStatusCode.BadRequest

        is ServerConnectError.NotListenUpServer -> HttpStatusCode.BadGateway

        is ServerConnectError.ServerNotReachable -> HttpStatusCode.ServiceUnavailable

        is ServerConnectError.VerificationFailed -> HttpStatusCode.ServiceUnavailable

        // Client-local: the wire never carries these variants. Branches exist for
        // exhaustiveness only; if either ever reaches the server, treat it as a
        // malformed client request.
        is ServerConnectError.LocalNetworkPermissionDenied -> HttpStatusCode.BadRequest

        is ServerConnectError.TlsFailure -> HttpStatusCode.BadRequest
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

private fun MetadataError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is MetadataError.ExternalRateLimited -> HttpStatusCode.TooManyRequests
        is MetadataError.ExternalUnavailable -> HttpStatusCode.ServiceUnavailable
        is MetadataError.NotFound -> HttpStatusCode.NotFound
        is MetadataError.Malformed -> HttpStatusCode.BadGateway
        is MetadataError.ChapterCountMismatch -> HttpStatusCode.UnprocessableEntity
    }

private fun LibraryError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is LibraryError.NotFound -> HttpStatusCode.NotFound
        is LibraryError.InvalidPath -> HttpStatusCode.BadRequest
        is LibraryError.DuplicateFolder -> HttpStatusCode.Conflict
        is LibraryError.FolderNotFound -> HttpStatusCode.NotFound
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

private fun TagError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is TagError.NotFound -> HttpStatusCode.NotFound
        is TagError.BookNotFound -> HttpStatusCode.NotFound
        is TagError.InvalidName -> HttpStatusCode.BadRequest
        is TagError.NameTooLong -> HttpStatusCode.BadRequest
    }

private fun MoodError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is MoodError.NotFound -> HttpStatusCode.NotFound
        is MoodError.BookNotFound -> HttpStatusCode.NotFound
        is MoodError.InvalidName -> HttpStatusCode.BadRequest
        is MoodError.NameTooLong -> HttpStatusCode.BadRequest
    }

private fun BookError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is BookError.NotFound -> HttpStatusCode.NotFound
        is BookError.InvalidInput -> HttpStatusCode.BadRequest
    }

private fun CoverError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is CoverError.NotPresent -> HttpStatusCode.NotFound
    }

private fun ContributorError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is ContributorError.NotFound -> HttpStatusCode.NotFound
        is ContributorError.InvalidInput -> HttpStatusCode.BadRequest
        is ContributorError.MergeSelfTarget -> HttpStatusCode.BadRequest
        is ContributorError.AliasNotFound -> HttpStatusCode.NotFound
    }

private fun SeriesError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is SeriesError.NotFound -> HttpStatusCode.NotFound
        is SeriesError.InvalidInput -> HttpStatusCode.BadRequest
        is SeriesError.MergeSelfTarget -> HttpStatusCode.BadRequest
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

private fun ShelfError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is ShelfError.NotFound -> HttpStatusCode.NotFound
        is ShelfError.Forbidden -> HttpStatusCode.Forbidden
        is ShelfError.InvalidName -> HttpStatusCode.BadRequest
    }

/**
 * Re-dispatches the grouped `ShelfError`/`SocialError` branch of [toHttpStatus] to each family's
 * own exhaustive mapping. Split out solely to keep [toHttpStatus]'s cyclomatic complexity under the
 * project threshold; the `else` is unreachable (only called from the grouped branch above).
 */
private fun AppError.shelfOrSocialHttpStatus(): HttpStatusCode =
    when (this) {
        is ShelfError -> toHttpStatus()
        is SocialError -> toHttpStatus()
        else -> HttpStatusCode.InternalServerError // unreachable: only called from the grouped branch
    }

private fun SocialError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is SocialError.NotFound -> HttpStatusCode.NotFound
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

private fun InviteError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is InviteError.NotFound -> HttpStatusCode.NotFound
        is InviteError.Expired -> HttpStatusCode.Conflict
        is InviteError.AlreadyClaimed -> HttpStatusCode.Conflict
        is InviteError.EmailInUse -> HttpStatusCode.Conflict
        is InviteError.InvalidInput -> HttpStatusCode.BadRequest
    }

private fun ProfileError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is ProfileError.InvalidImage -> HttpStatusCode.UnprocessableEntity
        is ProfileError.WrongPassword -> HttpStatusCode.UnprocessableEntity
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
