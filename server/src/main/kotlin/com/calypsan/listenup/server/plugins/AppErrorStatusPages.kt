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
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.error.ProfileError
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.error.ServerConnectError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.error.TagError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AppErrorStatusPages")

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
        exception<Throwable> { call, ex ->
            // Cancellation must always re-raise — never swallow it.
            if (ex is CancellationException) throw ex
            val correlationId = call.callId
            logger.error("unhandled exception on {} correlationId={}", call.request.uri, correlationId, ex)
            val body: AppError = InternalError(correlationId)
            call.respond(HttpStatusCode.InternalServerError, body)
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, mapOf("error" to "not_found", "path" to call.request.uri))
        }
    }
}

/** Status mapping for typed [AppError]. Used by both REST handlers and tests. */
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
        is TagError -> toHttpStatus()
        is CollectionError -> toHttpStatus()
        is AdminError -> toHttpStatus()
        is InviteError -> toHttpStatus()
        is BookError -> toHttpStatus()
        is CoverError -> toHttpStatus()
        is ContributorError -> toHttpStatus()
        is SeriesError -> toHttpStatus()
        is GenreError -> toHttpStatus()
        is ProfileError -> toHttpStatus()
        is BackupError -> toHttpStatus()
        else -> fallbackToHttpStatus()
    }

/**
 * Status for the infrastructure/utility error subtypes — separated to keep [toHttpStatus]
 * within Detekt's cyclomatic-complexity limit while the exhaustive domain dispatch expands.
 * The `else` in the caller is safe: every sealed [AppError] subtype not matched there lands
 * here, and this `when` is still exhaustive over the remaining sealed subtypes.
 */
private fun AppError.fallbackToHttpStatus(): HttpStatusCode =
    when (this) {
        is ValidationError -> HttpStatusCode.BadRequest

        is InternalError -> HttpStatusCode.InternalServerError

        // TransportError and PlaybackError are client-local — they should never originate on
        // the server. If one escapes here it's a server bug; surface it as 500.
        is TransportError -> HttpStatusCode.InternalServerError

        is PlaybackError -> HttpStatusCode.InternalServerError

        else -> HttpStatusCode.InternalServerError
    }

/** Stamp the request's correlation id onto a typed wire error. */
internal fun AppError.withCorrelationId(id: String?): AppError =
    when (this) {
        is AuthError -> withCorrelationId(id)
        is DownloadError -> withCorrelationId(id)
        is ImportError -> withCorrelationId(id)
        is ScanError -> withCorrelationId(id)
        is ServerConnectError -> withCorrelationId(id)
        is SyncError -> withCorrelationId(id)
        is AudioMetadataError -> withCorrelationId(id)
        is LibraryError -> withCorrelationId(id)
        is MetadataError -> withCorrelationId(id)
        is TagError -> withCorrelationId(id)
        is CollectionError -> withCorrelationId(id)
        is AdminError -> withCorrelationId(id)
        is InviteError -> withCorrelationId(id)
        is BookError -> withCorrelationId(id)
        is CoverError -> withCorrelationId(id)
        is ContributorError -> withCorrelationId(id)
        is SeriesError -> withCorrelationId(id)
        is GenreError -> withCorrelationId(id)
        is ProfileError -> withCorrelationId(id)
        is BackupError -> withCorrelationId(id)
        else -> fallbackWithCorrelationId(id)
    }

/**
 * Correlation-id stamp for the infrastructure/utility error subtypes — separated to keep
 * [withCorrelationId] within Detekt's cyclomatic-complexity limit.
 */
private fun AppError.fallbackWithCorrelationId(id: String?): AppError =
    when (this) {
        is ValidationError -> copy(correlationId = id)
        is InternalError -> copy(correlationId = id)
        is TransportError -> withCorrelationId(id)
        is PlaybackError -> withCorrelationId(id)
        else -> this
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
        is DownloadError.TranscodeTimeout -> HttpStatusCode.ServiceUnavailable
    }

private fun DownloadError.withCorrelationId(id: String?): DownloadError =
    when (this) {
        is DownloadError.DownloadFailed -> copy(correlationId = id)
        is DownloadError.InsufficientStorage -> copy(correlationId = id)
        is DownloadError.TranscodeTimeout -> copy(correlationId = id)
    }

private fun ImportError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is ImportError.UploadFailed -> HttpStatusCode.ServiceUnavailable
        is ImportError.AnalysisFailed -> HttpStatusCode.ServiceUnavailable
        is ImportError.ApplyFailed -> HttpStatusCode.ServiceUnavailable
    }

private fun ImportError.withCorrelationId(id: String?): ImportError =
    when (this) {
        is ImportError.UploadFailed -> copy(correlationId = id)
        is ImportError.AnalysisFailed -> copy(correlationId = id)
        is ImportError.ApplyFailed -> copy(correlationId = id)
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
    }

private fun MetadataError.withCorrelationId(id: String?): MetadataError =
    when (this) {
        is MetadataError.ExternalRateLimited -> copy(correlationId = id)
        is MetadataError.ExternalUnavailable -> copy(correlationId = id)
        is MetadataError.NotFound -> copy(correlationId = id)
        is MetadataError.Malformed -> copy(correlationId = id)
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
        is CollectionError.InboxNotDeletable -> HttpStatusCode.BadRequest
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
        is CollectionError.InboxNotDeletable -> copy(correlationId = id)
        is CollectionError.SelfShare -> copy(correlationId = id)
        is CollectionError.AlreadyShared -> copy(correlationId = id)
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
