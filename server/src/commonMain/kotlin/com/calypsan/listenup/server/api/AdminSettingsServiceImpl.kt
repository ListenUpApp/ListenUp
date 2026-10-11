package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.dto.admin.AdminServerSettings
import com.calypsan.listenup.api.dto.admin.AdminServerSettingsPatch
import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.api.dto.admin.RatingSourceStatus
import com.calypsan.listenup.api.error.AdminError
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.hardcover.HardcoverSourceSettings
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.metadata.spi.RatingSource
import com.calypsan.listenup.server.metadata.spi.RatingSourceAvailability
import com.calypsan.listenup.server.ratings.RatingSourceSettings
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.LibraryRepository
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sidecar.SIDECAR_WRITES_ENABLED_KEY
import com.calypsan.listenup.server.sync.BookExternalRatingRepository
import com.calypsan.listenup.server.sync.ChangeBus
import kotlin.time.Clock
import com.calypsan.listenup.server.auth.requireAdmin

/** Max length for the operator-set server name. */
private const val MAX_SERVER_NAME = 100

/** Max length for the operator-set remote URL. */
private const val MAX_REMOTE_URL = 2048

/**
 * [AdminSettingsService] implementation — server-identity settings (server name, remote URL,
 * inbox-enabled gate, push-notifications toggle) backed by [ServerSettingsRepository] and
 * [LibraryRepository]. Admin-gated
 * via [requireAdmin]; route handlers bind the caller via [copyWith] (the Koin singleton carries
 * an unscoped placeholder).
 */
internal class AdminSettingsServiceImpl(
    private val settings: ServerSettingsRepository,
    private val changeBus: ChangeBus,
    private val libraryRegistry: LibraryRegistry,
    private val libraryRepository: LibraryRepository,
    private val principal: PrincipalProvider = PrincipalProvider.None,
    /** Per-source enabled flag + health for outside ratings. Nullable on the same terms as the
     * pair below — non-null in production, absent in the direct-construction unit tests that
     * never call [getRatingSources] / [setRatingSourceEnabled]. */
    private val sourceSettings: RatingSourceSettings? = null,
    /** The `book_external_ratings` repository — [setRatingSourceEnabled] flips its rows. */
    private val externalRatings: BookExternalRatingRepository? = null,
    /** Every registered [RatingSource] — what [getRatingSources] enumerates. */
    private val providerRegistry: MetadataProviderRegistry? = null,
    /**
     * Admin → Hardcover (#1542): the API token, the metadata switch, and whose account catalogue reads
     * use — which [getRatingSources] names on the Hardcover row.
     */
    private val hardcoverSource: HardcoverSourceSettings? = null,
) : AdminSettingsService {
    /** Returns a copy scoped to the given [provider]. Route handlers call this per-request. */
    fun copyWith(provider: PrincipalProvider): AdminSettingsServiceImpl =
        AdminSettingsServiceImpl(
            settings = settings,
            changeBus = changeBus,
            libraryRegistry = libraryRegistry,
            libraryRepository = libraryRepository,
            principal = provider,
            sourceSettings = sourceSettings,
            externalRatings = externalRatings,
            providerRegistry = providerRegistry,
            hardcoverSource = hardcoverSource,
        )

    override suspend fun getServerSettings(): AppResult<AdminServerSettings> {
        principal.requireAdmin()?.let { return AppResult.Failure(it) }
        return AppResult.Success(current())
    }

    override suspend fun updateServerSettings(patch: AdminServerSettingsPatch): AppResult<AdminServerSettings> {
        principal.requireAdmin()?.let { return AppResult.Failure(it) }
        // Validated before any write, so a refused field leaves every other field of the patch unapplied.
        val serverName = patch.serverName?.trim()
        val remoteUrl = patch.remoteUrl?.trim()
        val region = patch.metadataRegion?.run { trim().lowercase() }
        firstRefusal(serverName, remoteUrl, region)?.let { return AppResult.Failure(it) }
        var changed = false
        serverName?.let { name ->
            settings.setServerName(name)
            changed = true
        }
        remoteUrl?.let { url ->
            settings.setRemoteUrl(url)
            changed = true
        }
        patch.holdNewBooksForReview?.let { enabled ->
            when (val r = libraryRepository.setHoldNewBooksForReview(libraryRegistry.currentLibrary(), enabled)) {
                is AppResult.Failure -> return AppResult.Failure(r.error)
                is AppResult.Success -> changed = true
            }
        }
        patch.pushNotificationsEnabled?.let { enabled ->
            settings.setPushNotificationsEnabled(enabled)
            changed = true
        }
        patch.sidecarWritesEnabled?.let { enabled ->
            settings.setValue(SIDECAR_WRITES_ENABLED_KEY, enabled.toString())
            changed = true
        }
        region?.let { newRegion ->
            when (val r = libraryRepository.setMetadataRegion(libraryRegistry.currentLibrary(), newRegion)) {
                is AppResult.Failure -> return AppResult.Failure(r.error)
                is AppResult.Success -> changed = true
            }
        }
        // Nudge every connected client to re-fetch getServerInfo so an admin's new name/remote URL
        // reaches them without a cold start. Content-free broadcast — carries no per-user data.
        if (changed) changeBus.broadcastControl(SyncControl.ServerInfoChanged)
        return AppResult.Success(current())
    }

    override suspend fun getRatingSources(): AppResult<List<RatingSourceStatus>> {
        principal.requireAdmin()?.let { return AppResult.Failure(it) }
        return AppResult.Success(ratingSourceStatuses())
    }

    override suspend fun setRatingSourceEnabled(
        source: ExternalRatingSource,
        enabled: Boolean,
    ): AppResult<List<RatingSourceStatus>> {
        principal.requireAdmin()?.let { return AppResult.Failure(it) }
        val sources = requireNotNull(sourceSettings) { "AdminSettingsServiceImpl.sourceSettings not wired" }
        val ratings = requireNotNull(externalRatings) { "AdminSettingsServiceImpl.externalRatings not wired" }
        sources.setEnabled(source, enabled)
        // Flips every existing row too — an offline client mirrors book_external_ratings and has
        // no other way to learn a source went dark (see the plan's "disabled sources" deviation).
        ratings.setSourceEnabled(source, enabled)
        return AppResult.Success(ratingSourceStatuses())
    }

    override suspend fun getHardcoverSource(): AppResult<HardcoverSourceStatus> {
        principal.requireAdmin()?.let { return AppResult.Failure(it) }
        return AppResult.Success(requireHardcoverSource().status())
    }

    override suspend fun setHardcoverApiToken(token: String): AppResult<HardcoverSourceStatus> {
        principal.requireAdmin()?.let { return AppResult.Failure(it) }
        return requireHardcoverSource().setApiToken(token)
    }

    override suspend fun clearHardcoverApiToken(): AppResult<HardcoverSourceStatus> {
        principal.requireAdmin()?.let { return AppResult.Failure(it) }
        return AppResult.Success(requireHardcoverSource().clearApiToken())
    }

    override suspend fun setHardcoverMetadataEnabled(enabled: Boolean): AppResult<HardcoverSourceStatus> {
        principal.requireAdmin()?.let { return AppResult.Failure(it) }
        return AppResult.Success(requireHardcoverSource().setMetadataEnabled(enabled))
    }

    private fun requireHardcoverSource(): HardcoverSourceSettings =
        requireNotNull(hardcoverSource) { "AdminSettingsServiceImpl.hardcoverSource not wired" }

    /** Every registered [RatingSource], with its enabled flag and health, as the admin sees it. */
    private suspend fun ratingSourceStatuses(): List<RatingSourceStatus> {
        val sources = sourceSettings ?: return emptyList()
        val registry = providerRegistry ?: return emptyList()
        val now = Clock.System.now().toEpochMilliseconds()
        return registry.capable<RatingSource>().map { source ->
            val (lastFetchedAt, lastError) = sources.health(source.ratingSource)
            RatingSourceStatus(
                source = source.ratingSource,
                enabled = sources.isEnabled(source.ratingSource),
                lastFetchedAt = lastFetchedAt,
                lastError = lastError,
                pausedUntil = sources.pausedUntil(source.ratingSource, now),
                unavailable = (source.availability() as? RatingSourceAvailability.Unavailable)?.reason,
                connectionUsername =
                    if (source.ratingSource == ExternalRatingSource.HARDCOVER) {
                        hardcoverSource?.accountName()
                    } else {
                        null
                    },
            )
        }
    }

    private suspend fun current(): AdminServerSettings =
        AdminServerSettings(
            serverName = settings.serverName(),
            remoteUrl = settings.remoteUrl(),
            holdNewBooksForReview = libraryRepository.readHoldNewBooksForReview(libraryRegistry.currentLibrary()),
            pushNotificationsEnabled = settings.pushNotificationsEnabled(),
            // Absent key = enabled (spec: sidecar writes are on by default).
            sidecarWritesEnabled = settings.getValue(SIDECAR_WRITES_ENABLED_KEY)?.toBooleanStrictOrNull() != false,
            metadataRegion =
                libraryRepository.readMetadataRegion(libraryRegistry.currentLibrary()) ?: MetadataLocale.DEFAULT.region,
        )
}

/** The first reason the trimmed patch fields cannot be saved, or null when all of them can. */
private fun firstRefusal(
    serverName: String?,
    remoteUrl: String?,
    region: String?,
): AppError? =
    when {
        region != null && MetadataLocale.SUPPORTED.none { it.region == region } -> AdminError.InvalidInput()
        serverName != null && (serverName.isBlank() || serverName.length > MAX_SERVER_NAME) -> AdminError.InvalidInput()
        remoteUrl != null -> remoteUrlRefusal(remoteUrl)
        else -> null
    }

/** Blank clears the remote URL; anything else must be a plain http(s) URL, the shape every app can use. */
private fun remoteUrlRefusal(remoteUrl: String): ValidationError? =
    when {
        remoteUrl.length > MAX_REMOTE_URL -> {
            ValidationError(message = "Keep the remote address to $MAX_REMOTE_URL characters.", field = "remoteUrl")
        }

        remoteUrl.isNotEmpty() && !isHttpUrl(remoteUrl) -> {
            ValidationError(
                message = "Enter the remote address as a full URL starting with http:// or https://.",
                field = "remoteUrl",
            )
        }

        else -> {
            null
        }
    }

/** Whether [value] is a plain absolute http(s) URL, by the same rules the apps apply to a server address. */
private fun isHttpUrl(value: String): Boolean =
    try {
        ServerUrl(value)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
