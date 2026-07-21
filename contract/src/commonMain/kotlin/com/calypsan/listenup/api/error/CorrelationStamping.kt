package com.calypsan.listenup.api.error

/**
 * Stamps the request's correlation id onto a typed [AppError].
 *
 * Canonical home for correlation-id stamping, shared by both server-push transports: the REST
 * route folds ([com.calypsan.listenup.server.plugins] `respondAppResult`) and the RPC exception
 * guard (`stampAndLogFailure`, generated into `:contract`). Living in `:contract` — where every
 * [AppError] subtype is defined — keeps a single exhaustive implementation so a new error family
 * can't be stamped on one transport and forgotten on the other.
 *
 * Exhaustive over all direct [AppError] implementors. The single-variant leaf families
 * ([ValidationError], [InternalError]) and the client-local types ([TransportError],
 * [PlaybackError]) are delegated to [leafWithCorrelationId] to keep the cyclomatic complexity of
 * this function under the project threshold of 25.
 */
public fun AppError.withCorrelationId(id: String?): AppError =
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

        // TagError + MoodError share one branch (delegating to an exhaustive helper) to keep
        // this function's cyclomatic complexity under the project threshold while preserving
        // per-variant exhaustiveness for both families.
        is TagError, is MoodError -> tagOrMoodWithCorrelationId(id)

        is CollectionError -> withCorrelationId(id)

        is ShelfError -> withCorrelationId(id)

        is SocialError -> withCorrelationId(id)

        is AdminError -> withCorrelationId(id)

        is InviteError -> withCorrelationId(id)

        is BookError -> withCorrelationId(id)

        is CoverError -> withCorrelationId(id)

        is ContributorError -> withCorrelationId(id)

        is SeriesError -> withCorrelationId(id)

        is GenreError -> withCorrelationId(id)

        is ProfileError -> withCorrelationId(id)

        is BackupError -> withCorrelationId(id)

        is ValidationError, is InternalError, is TransportError, is PlaybackError -> leafWithCorrelationId(id)
    }

/**
 * Stamps a correlation id onto the "leaf" error families that have no nested `when` of their own:
 * [ValidationError] and [InternalError] (single-variant `copy`) plus the client-local
 * [TransportError] and [PlaybackError].
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
        is AuthError.RegistrationNotFound -> copy(correlationId = id)
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
        is TransportError.OutcomeUnknown -> copy(correlationId = id)
        is TransportError.Server4xx -> copy(correlationId = id)
        is TransportError.Server5xx -> copy(correlationId = id)
        is TransportError.DataMalformed -> copy(correlationId = id)
        is TransportError.ContractMismatch -> copy(correlationId = id)
    }

private fun SyncError.withCorrelationId(id: String?): SyncError =
    when (this) {
        is SyncError.SyncFailed -> copy(correlationId = id)
        is SyncError.RealtimeDisconnected -> copy(correlationId = id)
        is SyncError.PushFailed -> copy(correlationId = id)
        is SyncError.NotFound -> copy(correlationId = id)
    }

private fun DownloadError.withCorrelationId(id: String?): DownloadError =
    when (this) {
        is DownloadError.DownloadFailed -> copy(correlationId = id)
        is DownloadError.InsufficientStorage -> copy(correlationId = id)
    }

private fun ImportError.withCorrelationId(id: String?): ImportError =
    when (this) {
        is ImportError.UploadFailed -> copy(correlationId = id)
        is ImportError.AnalysisFailed -> copy(correlationId = id)
        is ImportError.ApplyFailed -> copy(correlationId = id)
        is ImportError.ImportNotFound -> copy(correlationId = id)
        is ImportError.MappingInvalid -> copy(correlationId = id)
    }

private fun ServerConnectError.withCorrelationId(id: String?): ServerConnectError =
    when (this) {
        is ServerConnectError.InvalidUrl -> copy(correlationId = id)
        is ServerConnectError.NotListenUpServer -> copy(correlationId = id)
        is ServerConnectError.ServerNotReachable -> copy(correlationId = id)
        is ServerConnectError.VerificationFailed -> copy(correlationId = id)
        is ServerConnectError.LocalNetworkPermissionDenied -> copy(correlationId = id)
        is ServerConnectError.TlsFailure -> copy(correlationId = id)
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

private fun MetadataError.withCorrelationId(id: String?): MetadataError =
    when (this) {
        is MetadataError.ExternalRateLimited -> copy(correlationId = id)
        is MetadataError.ExternalUnavailable -> copy(correlationId = id)
        is MetadataError.NotFound -> copy(correlationId = id)
        is MetadataError.Malformed -> copy(correlationId = id)
        is MetadataError.ChapterCountMismatch -> copy(correlationId = id)
    }

private fun LibraryError.withCorrelationId(id: String?): LibraryError =
    when (this) {
        is LibraryError.NotFound -> copy(correlationId = id)
        is LibraryError.InvalidPath -> copy(correlationId = id)
        is LibraryError.DuplicateFolder -> copy(correlationId = id)
        is LibraryError.FolderNotFound -> copy(correlationId = id)
    }

private fun TagError.withCorrelationId(id: String?): TagError =
    when (this) {
        is TagError.NotFound -> copy(correlationId = id)
        is TagError.BookNotFound -> copy(correlationId = id)
        is TagError.InvalidName -> copy(correlationId = id)
        is TagError.NameTooLong -> copy(correlationId = id)
    }

private fun MoodError.withCorrelationId(id: String?): MoodError =
    when (this) {
        is MoodError.NotFound -> copy(correlationId = id)
        is MoodError.BookNotFound -> copy(correlationId = id)
        is MoodError.InvalidName -> copy(correlationId = id)
        is MoodError.NameTooLong -> copy(correlationId = id)
    }

private fun BookError.withCorrelationId(id: String?): BookError =
    when (this) {
        is BookError.NotFound -> copy(correlationId = id)
        is BookError.InvalidInput -> copy(correlationId = id)
    }

private fun CoverError.withCorrelationId(id: String?): CoverError =
    when (this) {
        is CoverError.NotPresent -> copy(correlationId = id)
    }

private fun ContributorError.withCorrelationId(id: String?): ContributorError =
    when (this) {
        is ContributorError.NotFound -> copy(correlationId = id)
        is ContributorError.InvalidInput -> copy(correlationId = id)
        is ContributorError.MergeSelfTarget -> copy(correlationId = id)
        is ContributorError.AliasNotFound -> copy(correlationId = id)
    }

private fun SeriesError.withCorrelationId(id: String?): SeriesError =
    when (this) {
        is SeriesError.NotFound -> copy(correlationId = id)
        is SeriesError.InvalidInput -> copy(correlationId = id)
        is SeriesError.MergeSelfTarget -> copy(correlationId = id)
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

private fun ShelfError.withCorrelationId(id: String?): ShelfError =
    when (this) {
        is ShelfError.NotFound -> copy(correlationId = id)
        is ShelfError.Forbidden -> copy(correlationId = id)
        is ShelfError.InvalidName -> copy(correlationId = id)
    }

private fun SocialError.withCorrelationId(id: String?): SocialError =
    when (this) {
        is SocialError.NotFound -> copy(correlationId = id)
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

private fun InviteError.withCorrelationId(id: String?): InviteError =
    when (this) {
        is InviteError.NotFound -> copy(correlationId = id)
        is InviteError.Expired -> copy(correlationId = id)
        is InviteError.AlreadyClaimed -> copy(correlationId = id)
        is InviteError.EmailInUse -> copy(correlationId = id)
        is InviteError.InvalidInput -> copy(correlationId = id)
    }

private fun ProfileError.withCorrelationId(id: String?): ProfileError =
    when (this) {
        is ProfileError.InvalidImage -> copy(correlationId = id)
        is ProfileError.WrongPassword -> copy(correlationId = id)
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
