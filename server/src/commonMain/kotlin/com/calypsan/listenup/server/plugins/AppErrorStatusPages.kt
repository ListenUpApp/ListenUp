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
import com.calypsan.listenup.api.error.LibraryWriteError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.error.MoodError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.error.ProfileError
import com.calypsan.listenup.api.error.PushError
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.error.ServerConnectError
import com.calypsan.listenup.api.error.ShelfError
import com.calypsan.listenup.api.error.SocialError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.error.TranscodeError
import com.calypsan.listenup.api.error.TagError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.UnknownError
import com.calypsan.listenup.api.error.UploadError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.error.withCorrelationId as stampCorrelationId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.io.MalformedMultipartException
import com.calypsan.listenup.server.io.MultipartPartTooLargeException
import io.ktor.util.AttributeKey
import io.ktor.server.application.ApplicationCall
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
            // Only describe a 404 that no route produced. A route that deliberately answered with a
            // typed AppError has already written the body the client needs.
            if (!call.attributes.contains(TypedAppErrorSent)) {
                call.respond(status, mapOf("error" to "not_found", "path" to call.request.uri))
            }
        }
    }
}

/**
 * Marks a call whose 404 body was written deliberately by a route, so the catch-all
 * [io.ktor.server.plugins.statuspages.StatusPagesConfig.status] hook leaves it alone.
 *
 * Without it that hook rewrites the body of **every** 404 — including a typed [AppError] a route
 * meant to send — and the client decodes a generic `not_found` instead. `ImportError.ImportNotFound`
 * has shipped with exactly that defect: its typed body is replaced before it ever reaches a client,
 * unnoticed because nothing decoded it.
 */
private val TypedAppErrorSent = AttributeKey<Unit>("ListenUpTypedAppErrorSent")

/**
 * Sends [error] as a typed JSON body at its mapped status, stamped with this call's correlation id.
 *
 * The single responder for every non-RPC route — six byte-identical private copies of it existed
 * before, which is also why the 404 defect above could not be fixed in one place.
 */
internal suspend fun ApplicationCall.respondAppError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    attributes.put(TypedAppErrorSent, Unit)
    respond(typed.toHttpStatus(), typed)
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

        // ImportError + UploadError share one branch (delegating to an exhaustive helper) for the
        // same reason as the grouped branches below: it keeps this function's cyclomatic
        // complexity under the project threshold. Both are content arriving from outside the
        // server — someone else's backup, and someone's own files.
        is ImportError, is UploadError -> arrivalFamilyHttpStatus()

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

        // ShelfError + SocialError share one branch (delegating to an exhaustive helper) to keep
        // this function's cyclomatic complexity under the project threshold while preserving
        // per-variant exhaustiveness for both families.
        is ShelfError, is SocialError -> shelfOrSocialHttpStatus()

        is AdminError -> toHttpStatus()

        is InviteError -> toHttpStatus()

        is BookError -> toHttpStatus()

        // CoverError + TranscodeError share one branch (delegating to an exhaustive helper) for
        // the same reason as the grouped branches above: it keeps this function's cyclomatic
        // complexity under the project threshold. Both are media the server derives on demand.
        is CoverError, is TranscodeError -> derivedMediaHttpStatus()

        is ContributorError -> toHttpStatus()

        is SeriesError -> toHttpStatus()

        is GenreError -> toHttpStatus()

        is ProfileError -> toHttpStatus()

        is BackupError -> toHttpStatus()

        is PushError -> toHttpStatus()

        is ValidationError -> HttpStatusCode.BadRequest

        // InternalError, TransportError, PlaybackError, and UnknownError are all server-bug /
        // client-local paths; grouped into a single branch so the function stays under the
        // cyclomatic-complexity threshold while remaining exhaustive — a new AppError subtype will
        // still fail this when at compile time. UnknownError is receive-only (the polymorphic
        // fallback in contractJson); a server holding one means it decoded an error family it does
        // not know, which is a 500 by definition.
        is InternalError, is TransportError, is PlaybackError, is UnknownError,
        -> HttpStatusCode.InternalServerError
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
        is AuthError.ResetRequestNotFound -> HttpStatusCode.NotFound
        is AuthError.ResetNotApproved -> HttpStatusCode.Forbidden
        is AuthError.ResetCodeIncorrect -> HttpStatusCode.BadRequest
        is AuthError.ResetAttemptsExhausted -> HttpStatusCode.TooManyRequests
        is AuthError.RootResetUnavailable -> HttpStatusCode.Forbidden
    }

private fun ScanError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is ScanError.AlreadyRunning -> HttpStatusCode.Conflict

        is ScanError.LibraryPathNotConfigured -> HttpStatusCode.ServiceUnavailable

        is ScanError.LibraryPathNotFound -> HttpStatusCode.ServiceUnavailable

        // Not a server fault: the folder simply holds no audio. 422 says "I understood the
        // request and the content is unusable", which is exactly the situation.
        is ScanError.NoRecognizedAudio -> HttpStatusCode.UnprocessableEntity

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

        // These three are RPC-only refusals (the REST catch-up routes they replaced are gone),
        // but the mapping stays total so any future HTTP surface renders them sanely.
        is SyncError.UnknownDomain -> HttpStatusCode.NotFound

        is SyncError.TooManyIds -> HttpStatusCode.BadRequest

        is SyncError.UnsupportedMatch -> HttpStatusCode.BadRequest
    }

private fun DownloadError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is DownloadError.DownloadFailed -> HttpStatusCode.ServiceUnavailable

        is DownloadError.InsufficientStorage -> HttpStatusCode.InsufficientStorage

        is DownloadError.DeleteFailed -> HttpStatusCode.InternalServerError

        // Never actually minted server-side — NotSupported is a client-local fact about a
        // platform's download backend (see NoDownloadsService) that never crosses the wire.
        // Mapped for exhaustiveness only.
        is DownloadError.NotSupported -> HttpStatusCode.ServiceUnavailable
    }

/**
 * Status mapping for the content-arrival families, [ImportError] and [UploadError], which
 * [toHttpStatus] dispatches to from the single grouped branch above.
 */
private fun AppError.arrivalFamilyHttpStatus(): HttpStatusCode =
    when (this) {
        is ImportError -> toHttpStatus()
        is UploadError -> toHttpStatus()
        else -> HttpStatusCode.InternalServerError // unreachable: only called from the grouped branch
    }

private fun ImportError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is ImportError.UploadFailed -> HttpStatusCode.UnprocessableEntity
        is ImportError.AnalysisFailed -> HttpStatusCode.ServiceUnavailable
        is ImportError.ApplyFailed -> HttpStatusCode.ServiceUnavailable
        is ImportError.ImportNotFound -> HttpStatusCode.NotFound
        is ImportError.MappingInvalid -> HttpStatusCode.BadRequest
    }

private fun UploadError.toHttpStatus(): HttpStatusCode =
    when (this) {
        // 410 Gone, not 404, for two reasons that agree. Semantically an upload session is a
        // resource that existed and has since been finalized or abandoned, which is exactly what
        // Gone means. Practically, [installAppErrorStatusPages] installs a `status(NotFound)`
        // handler that rewrites the body of EVERY 404 — including one a route deliberately sent —
        // into a generic `{"error":"not_found"}`, so a typed 404 body never reaches the client at
        // all. The upload client has to tell "start a fresh session" apart from every other
        // failure, and it needs the typed value to do it.
        is UploadError.SessionNotFound -> HttpStatusCode.Gone

        is UploadError.InvalidFilePath -> HttpStatusCode.BadRequest

        is UploadError.SessionTooLarge -> HttpStatusCode.PayloadTooLarge

        is UploadError.FileTransferFailed -> HttpStatusCode.UnprocessableEntity

        is UploadError.NoBooksFound -> HttpStatusCode.UnprocessableEntity

        is UploadError.NoLibraryFolder -> HttpStatusCode.ServiceUnavailable
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

/**
 * Status mapping for the library-folder error families, [LibraryError] and [LibraryWriteError].
 *
 * Split from [toHttpStatus] solely to keep that function's cyclomatic complexity under the
 * project threshold. The `else` branch is unreachable — this is only called from the single
 * grouped branch in [toHttpStatus].
 */
private fun AppError.libraryFamilyHttpStatus(): HttpStatusCode =
    when (this) {
        is LibraryError -> toHttpStatus()
        is LibraryWriteError -> toHttpStatus()
        else -> HttpStatusCode.InternalServerError // unreachable: only called from the grouped branch
    }

private fun LibraryWriteError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is LibraryWriteError.Unavailable -> HttpStatusCode.ServiceUnavailable

        // Not 503: the mount is fine, the path is wrong. Mirrors LibraryError.InvalidPath, and
        // keeps a caller bug out of the retryable-5xx bucket.
        is LibraryWriteError.OutsideLibrary -> HttpStatusCode.BadRequest

        // Also a caller bug rather than a mount problem: the path is inside the library but is not
        // the caller's to destroy (a folder root, or a symlink standing in for a book directory).
        is LibraryWriteError.ProtectedPath -> HttpStatusCode.BadRequest
    }

private fun LibraryError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is LibraryError.NotFound -> HttpStatusCode.NotFound
        is LibraryError.InvalidPath -> HttpStatusCode.BadRequest
        is LibraryError.DuplicateFolder -> HttpStatusCode.Conflict
        is LibraryError.FolderNotFound -> HttpStatusCode.NotFound
        is LibraryError.BrowseTimedOut -> HttpStatusCode.GatewayTimeout
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

        // 409, not 400: the request is well-formed and the book exists — it is the current state of
        // the library (two books in one folder) that makes the delete unsafe.
        is BookError.FolderNotExclusive -> HttpStatusCode.Conflict
    }

private fun CoverError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is CoverError.NotPresent -> HttpStatusCode.NotFound
    }

/**
 * Re-dispatches the grouped `CoverError`/`TranscodeError` branch of [toHttpStatus] to each family's
 * own exhaustive mapping. Split out solely to keep [toHttpStatus]'s cyclomatic complexity under the
 * project threshold; the `else` is unreachable (only called from the grouped branch above).
 */
private fun AppError.derivedMediaHttpStatus(): HttpStatusCode =
    when (this) {
        is CoverError -> toHttpStatus()
        is TranscodeError -> toHttpStatus()
        else -> HttpStatusCode.InternalServerError // unreachable: only called from the grouped branch
    }

private fun TranscodeError.toHttpStatus(): HttpStatusCode =
    when (this) {
        // Retryable: the admission gate refused now, but a slot frees up.
        is TranscodeError.TranscoderBusy -> HttpStatusCode.ServiceUnavailable

        // Permanent for this server: there is no encoder, so retrying changes nothing.
        is TranscodeError.TranscoderUnavailable -> HttpStatusCode.NotImplemented

        is TranscodeError.TranscodeFailed -> HttpStatusCode.InternalServerError
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

private fun PushError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is PushError.PushDisabled -> HttpStatusCode.ServiceUnavailable
    }
