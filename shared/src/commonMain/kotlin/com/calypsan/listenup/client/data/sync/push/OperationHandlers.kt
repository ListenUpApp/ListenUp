package com.calypsan.listenup.client.data.sync.push

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.appJson
import com.calypsan.listenup.client.core.currentEpochMilliseconds
import com.calypsan.listenup.client.data.local.db.OperationType
import com.calypsan.listenup.client.data.local.db.PendingOperationEntity
import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.remote.BookApiContract
import com.calypsan.listenup.client.data.remote.ShelfApiContract
import com.calypsan.listenup.client.data.remote.BookUpdateRequest
import com.calypsan.listenup.client.data.remote.ContributorApiContract
import com.calypsan.listenup.client.data.remote.ListeningEventRequest
import com.calypsan.listenup.client.data.remote.ProfileApiContract
import com.calypsan.listenup.client.data.remote.SeriesApiContract
import com.calypsan.listenup.client.data.remote.SeriesUpdateRequest
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.UpdateContributorRequest
import com.calypsan.listenup.client.data.remote.UserPreferencesApiContract
import com.calypsan.listenup.client.data.remote.UserPreferencesRequest
import com.calypsan.listenup.client.data.sync.ImageDownloaderContract
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private val logger = KotlinLogging.logger {}

/**
 * Handler for BOOK_UPDATE operations.
 * Coalesces updates to the same book, merging fields.
 */
class BookUpdateHandler(
    private val api: BookApiContract,
) : OperationHandler<BookUpdatePayload> {
    override val operationType = OperationType.BOOK_UPDATE

    override fun parsePayload(json: String): BookUpdatePayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: BookUpdatePayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: BookUpdatePayload,
        newPayload: BookUpdatePayload,
    ): BookUpdatePayload? {
        if (existing.operationType != OperationType.BOOK_UPDATE) return null
        return BookUpdatePayload(
            title = newPayload.title ?: existingPayload.title,
            subtitle = newPayload.subtitle ?: existingPayload.subtitle,
            description = newPayload.description ?: existingPayload.description,
            publisher = newPayload.publisher ?: existingPayload.publisher,
            publishYear = newPayload.publishYear ?: existingPayload.publishYear,
            language = newPayload.language ?: existingPayload.language,
            isbn = newPayload.isbn ?: existingPayload.isbn,
            asin = newPayload.asin ?: existingPayload.asin,
            abridged = newPayload.abridged ?: existingPayload.abridged,
            createdAt = newPayload.createdAt ?: existingPayload.createdAt,
        )
    }

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: BookUpdatePayload,
    ): AppResult<Unit> {
        val request =
            BookUpdateRequest(
                title = payload.title,
                subtitle = payload.subtitle,
                description = payload.description,
                publisher = payload.publisher,
                publishYear = payload.publishYear,
                language = payload.language,
                isbn = payload.isbn,
                asin = payload.asin,
                abridged = payload.abridged,
                createdAt = payload.createdAt,
            )
        return when (val result = api.updateBook(operation.entityId!!, request)) {
            is Success -> Success(Unit)
            is Failure -> result
        }
    }
}

/**
 * Handler for CONTRIBUTOR_UPDATE operations.
 * Coalesces updates to the same contributor.
 */
class ContributorUpdateHandler(
    private val api: ContributorApiContract,
) : OperationHandler<ContributorUpdatePayload> {
    override val operationType = OperationType.CONTRIBUTOR_UPDATE

    override fun parsePayload(json: String): ContributorUpdatePayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: ContributorUpdatePayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: ContributorUpdatePayload,
        newPayload: ContributorUpdatePayload,
    ): ContributorUpdatePayload? {
        if (existing.operationType != OperationType.CONTRIBUTOR_UPDATE) return null
        return ContributorUpdatePayload(
            name = newPayload.name ?: existingPayload.name,
            biography = newPayload.biography ?: existingPayload.biography,
            website = newPayload.website ?: existingPayload.website,
            birthDate = newPayload.birthDate ?: existingPayload.birthDate,
            deathDate = newPayload.deathDate ?: existingPayload.deathDate,
            aliases = newPayload.aliases ?: existingPayload.aliases,
        )
    }

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: ContributorUpdatePayload,
    ): AppResult<Unit> {
        // API requires all fields, so we need the full update
        val request =
            UpdateContributorRequest(
                name = payload.name ?: "",
                biography = payload.biography,
                website = payload.website,
                birthDate = payload.birthDate,
                deathDate = payload.deathDate,
                aliases = payload.aliases ?: emptyList(),
            )
        return when (val result = api.updateContributor(operation.entityId!!, request)) {
            is Success -> Success(Unit)
            is Failure -> result
        }
    }
}

/**
 * Handler for SERIES_UPDATE operations.
 * Coalesces updates to the same series.
 */
class SeriesUpdateHandler(
    private val api: SeriesApiContract,
) : OperationHandler<SeriesUpdatePayload> {
    override val operationType = OperationType.SERIES_UPDATE

    override fun parsePayload(json: String): SeriesUpdatePayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: SeriesUpdatePayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: SeriesUpdatePayload,
        newPayload: SeriesUpdatePayload,
    ): SeriesUpdatePayload? {
        if (existing.operationType != OperationType.SERIES_UPDATE) return null
        return SeriesUpdatePayload(
            name = newPayload.name ?: existingPayload.name,
            description = newPayload.description ?: existingPayload.description,
        )
    }

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: SeriesUpdatePayload,
    ): AppResult<Unit> {
        val request =
            SeriesUpdateRequest(
                name = payload.name,
                description = payload.description,
            )
        return when (val result = api.updateSeries(operation.entityId!!, request)) {
            is Success -> Success(Unit)
            is Failure -> result
        }
    }
}

/**
 * Handler for SET_BOOK_CONTRIBUTORS operations.
 * Replaces entirely - newer operation wins.
 */
class SetBookContributorsHandler(
    private val api: BookApiContract,
) : OperationHandler<SetBookContributorsPayload> {
    override val operationType = OperationType.SET_BOOK_CONTRIBUTORS

    override fun parsePayload(json: String): SetBookContributorsPayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: SetBookContributorsPayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: SetBookContributorsPayload,
        newPayload: SetBookContributorsPayload,
    ): SetBookContributorsPayload? {
        if (existing.operationType != OperationType.SET_BOOK_CONTRIBUTORS) return null
        return newPayload // Full replacement - new wins
    }

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: SetBookContributorsPayload,
    ): AppResult<Unit> {
        val contributors =
            payload.contributors.map { c ->
                com.calypsan.listenup.client.data.remote.ContributorInput(
                    name = c.name,
                    roles = c.roles,
                )
            }
        return when (val result = api.setBookContributors(operation.entityId!!, contributors)) {
            is Success -> Success(Unit)
            is Failure -> result
        }
    }
}

/**
 * Handler for SET_BOOK_SERIES operations.
 * Replaces entirely - newer operation wins.
 */
class SetBookSeriesHandler(
    private val api: BookApiContract,
) : OperationHandler<SetBookSeriesPayload> {
    override val operationType = OperationType.SET_BOOK_SERIES

    override fun parsePayload(json: String): SetBookSeriesPayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: SetBookSeriesPayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: SetBookSeriesPayload,
        newPayload: SetBookSeriesPayload,
    ): SetBookSeriesPayload? {
        if (existing.operationType != OperationType.SET_BOOK_SERIES) return null
        return newPayload // Full replacement - new wins
    }

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: SetBookSeriesPayload,
    ): AppResult<Unit> {
        val series =
            payload.series.map { s ->
                com.calypsan.listenup.client.data.remote.SeriesInput(
                    name = s.name,
                    sequence = s.sequence,
                )
            }
        return when (val result = api.setBookSeries(operation.entityId!!, series)) {
            is Success -> Success(Unit)
            is Failure -> result
        }
    }
}

/**
 * Handler for MERGE_CONTRIBUTOR operations.
 * Never coalesces - each merge is unique and order matters.
 */
class MergeContributorHandler(
    private val api: ContributorApiContract,
) : OperationHandler<MergeContributorPayload> {
    override val operationType = OperationType.MERGE_CONTRIBUTOR

    override fun parsePayload(json: String): MergeContributorPayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: MergeContributorPayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: MergeContributorPayload,
        newPayload: MergeContributorPayload,
    ): MergeContributorPayload? = null // Merge operations never coalesce

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: MergeContributorPayload,
    ): AppResult<Unit> =
        when (val result = api.mergeContributor(payload.targetId, payload.sourceId)) {
            is Success -> Success(Unit)
            is Failure -> result
        }
}

/**
 * Handler for UNMERGE_CONTRIBUTOR operations.
 * Never coalesces - each unmerge is unique and order matters.
 */
class UnmergeContributorHandler(
    private val api: ContributorApiContract,
) : OperationHandler<UnmergeContributorPayload> {
    override val operationType = OperationType.UNMERGE_CONTRIBUTOR

    override fun parsePayload(json: String): UnmergeContributorPayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: UnmergeContributorPayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: UnmergeContributorPayload,
        newPayload: UnmergeContributorPayload,
    ): UnmergeContributorPayload? = null // Unmerge operations never coalesce

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: UnmergeContributorPayload,
    ): AppResult<Unit> =
        when (val result = api.unmergeContributor(payload.contributorId, payload.aliasName)) {
            is Success -> Success(Unit)
            is Failure -> result
        }
}

/**
 * Handler for LISTENING_EVENT operations.
 * Never coalesces - each event is unique.
 * Batches together for efficient submission.
 *
 * After successful submission, marks the playback position as synced
 * so other parts of the system know the server has the latest data.
 */
class ListeningEventHandler(
    private val api: SyncApiContract,
    private val positionDao: PlaybackPositionDao,
) : OperationHandler<ListeningEventPayload> {
    override val operationType = OperationType.LISTENING_EVENT

    override fun parsePayload(json: String): ListeningEventPayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: ListeningEventPayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: ListeningEventPayload,
        newPayload: ListeningEventPayload,
    ): ListeningEventPayload? = null // Listening events never coalesce

    override fun batchKey(payload: ListeningEventPayload): String = "listening_events"

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: ListeningEventPayload,
    ): AppResult<Unit> {
        // Individual execution (fallback) - submit as single-item batch
        val request = listOf(payload.toApiRequest())
        return when (val result = api.submitListeningEvents(request)) {
            is Success -> {
                if (payload.id in result.data.acknowledged) {
                    // Mark position as synced so other parts of the system know
                    // the server has the latest data for this book
                    markPositionSynced(payload.bookId)
                    Success(Unit)
                } else {
                    Failure(Exception("Event not acknowledged by server"))
                }
            }

            is Failure -> {
                result
            }
        }
    }

    override suspend fun executeBatch(
        operations: List<Pair<PendingOperationEntity, ListeningEventPayload>>,
    ): Map<String, AppResult<Unit>> {
        logger.info { "📤 LISTENING EVENTS: Submitting ${operations.size} events to server" }
        val requests = operations.map { (_, payload) -> payload.toApiRequest() }
        logger.info { "📤 LISTENING EVENTS: Event IDs: ${requests.map { it.id }}" }

        return when (val result = api.submitListeningEvents(requests)) {
            is Success -> {
                logger.info {
                    "📤 LISTENING EVENTS: Server acknowledged ${result.data.acknowledged.size} events, failed ${result.data.failed.size}"
                }
                val acknowledged = result.data.acknowledged.toSet()
                operations.associate { (op, payload) ->
                    op.id to
                        if (payload.id in acknowledged) {
                            // Mark position as synced for this book
                            markPositionSynced(payload.bookId)
                            Success(Unit)
                        } else {
                            Failure(Exception("Event not acknowledged"))
                        }
                }
            }

            is Failure -> {
                (null as Exception?)?.let { logger.error(it) { "📤 LISTENING EVENTS: Submission failed" } }
                operations.associate { (op, _) -> op.id to result }
            }
        }
    }

    /**
     * Marks the playback position for a book as synced.
     * Called after successful event submission to indicate the server has the latest data.
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun markPositionSynced(bookId: String) {
        positionDao.markSynced(
            bookId = BookId(bookId),
            syncedAt = Clock.System.now().toEpochMilliseconds(),
        )
    }

    private fun ListeningEventPayload.toApiRequest() =
        ListeningEventRequest(
            id = id,
            book_id = bookId,
            start_position_ms = startPositionMs,
            end_position_ms = endPositionMs,
            started_at = startedAt,
            ended_at = endedAt,
            playback_speed = playbackSpeed,
            device_id = deviceId,
        )
}

/**
 * Handler for PLAYBACK_POSITION operations.
 * Coalesces by book - only the latest position matters.
 */
class PlaybackPositionHandler(
    private val api: SyncApiContract,
) : OperationHandler<PlaybackPositionPayload> {
    override val operationType = OperationType.PLAYBACK_POSITION

    override fun parsePayload(json: String): PlaybackPositionPayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: PlaybackPositionPayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: PlaybackPositionPayload,
        newPayload: PlaybackPositionPayload,
    ): PlaybackPositionPayload? {
        if (existing.operationType != OperationType.PLAYBACK_POSITION) return null
        return newPayload // Latest position wins
    }

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: PlaybackPositionPayload,
    ): AppResult<Unit> {
        // Position sync happens via listening events - the position is derived server-side
        // from the events. This is a proactive hint to the server.
        // For now, we submit as a minimal listening event.
        val event =
            ListeningEventRequest(
                id = operation.id,
                book_id = payload.bookId,
                start_position_ms = payload.positionMs,
                end_position_ms = payload.positionMs,
                started_at = payload.updatedAt,
                ended_at = payload.updatedAt,
                playback_speed = payload.playbackSpeed,
                device_id = "position_sync",
            )
        return when (val result = api.submitListeningEvents(listOf(event))) {
            is Success -> Success(Unit)
            is Failure -> result
        }
    }
}

/**
 * Handler for USER_PREFERENCES operations.
 * Always coalesces - only care about final state.
 */
class UserPreferencesHandler(
    private val api: UserPreferencesApiContract,
) : OperationHandler<UserPreferencesPayload> {
    override val operationType = OperationType.USER_PREFERENCES

    override fun parsePayload(json: String): UserPreferencesPayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: UserPreferencesPayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: UserPreferencesPayload,
        newPayload: UserPreferencesPayload,
    ): UserPreferencesPayload =
        // Always coalesces - only care about final state
        UserPreferencesPayload(
            defaultPlaybackSpeed = newPayload.defaultPlaybackSpeed ?: existingPayload.defaultPlaybackSpeed,
        )

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: UserPreferencesPayload,
    ): AppResult<Unit> {
        val request =
            UserPreferencesRequest(
                defaultPlaybackSpeed = payload.defaultPlaybackSpeed,
            )
        return when (val result = api.updatePreferences(request)) {
            is Success -> Success(Unit)
            is Failure -> result
        }
    }
}

/**
 * Handler for PROFILE_UPDATE operations.
 * Coalesces updates - only the final values matter.
 *
 * After successful push, syncs the server response to local UserEntity
 * so all UI observing the local cache sees the update.
 */
class ProfileUpdateHandler(
    private val transactionRunner: com.calypsan.listenup.client.data.local.db.TransactionRunner,
    private val api: ProfileApiContract,
    private val userDao: UserDao,
) : OperationHandler<ProfileUpdatePayload> {
    override val operationType = OperationType.PROFILE_UPDATE

    override fun parsePayload(json: String): ProfileUpdatePayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: ProfileUpdatePayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: ProfileUpdatePayload,
        newPayload: ProfileUpdatePayload,
    ): ProfileUpdatePayload? {
        if (existing.operationType != OperationType.PROFILE_UPDATE) return null
        // Don't coalesce if either has password change - passwords are independent operations
        if (existingPayload.newPassword != null || newPayload.newPassword != null) return null
        // Merge fields - new values override existing
        return ProfileUpdatePayload(
            tagline = newPayload.tagline ?: existingPayload.tagline,
            avatarType = newPayload.avatarType ?: existingPayload.avatarType,
            firstName = newPayload.firstName ?: existingPayload.firstName,
            lastName = newPayload.lastName ?: existingPayload.lastName,
        )
    }

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: ProfileUpdatePayload,
    ): AppResult<Unit> =
        when (
            val result =
                api.updateMyProfile(
                    avatarType = payload.avatarType,
                    tagline = payload.tagline,
                    firstName = payload.firstName,
                    lastName = payload.lastName,
                    newPassword = payload.newPassword,
                )
        ) {
            is Success -> {
                // Sync server response to local cache. The tagline, avatar, and
                // name fields all belong to the same user row — applying them
                // atomically ensures observers never see a partial update where
                // e.g. the name changed but the tagline didn't (Finding 05 D2).
                val profile = result.data
                transactionRunner.atomically {
                    userDao.updateTagline(
                        userId = profile.userId,
                        tagline = profile.tagline,
                        updatedAt = currentEpochMilliseconds(),
                    )
                    if (payload.avatarType != null) {
                        userDao.updateAvatar(
                            userId = profile.userId,
                            avatarType = profile.avatarType,
                            avatarValue = profile.avatarValue,
                            avatarColor = profile.avatarColor,
                            updatedAt = currentEpochMilliseconds(),
                        )
                    }
                    if (payload.firstName != null || payload.lastName != null) {
                        userDao.updateName(
                            userId = profile.userId,
                            firstName = profile.firstName,
                            lastName = profile.lastName,
                            displayName = profile.displayName,
                            updatedAt = currentEpochMilliseconds(),
                        )
                    }
                }
                Success(Unit)
            }

            is Failure -> {
                result
            }
        }
}

/**
 * Handler for PROFILE_AVATAR operations.
 * Replaces entirely - newer avatar upload wins.
 *
 * After successful upload:
 * 1. Syncs the server response (including the new avatar path) to local UserEntity
 * 2. Downloads the avatar image and saves it locally for offline access
 */
class ProfileAvatarHandler(
    private val api: ProfileApiContract,
    private val userDao: UserDao,
    private val imageDownloader: ImageDownloaderContract,
) : OperationHandler<ProfileAvatarPayload> {
    override val operationType = OperationType.PROFILE_AVATAR

    override fun parsePayload(json: String): ProfileAvatarPayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: ProfileAvatarPayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: ProfileAvatarPayload,
        newPayload: ProfileAvatarPayload,
    ): ProfileAvatarPayload? {
        if (existing.operationType != OperationType.PROFILE_AVATAR) return null
        // Full replacement - new avatar wins
        return newPayload
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: ProfileAvatarPayload,
    ): AppResult<Unit> {
        val imageData = Base64.decode(payload.imageDataBase64)
        return when (val result = api.uploadAvatar(imageData, payload.contentType)) {
            is Success -> {
                val profile = result.data

                // IMPORTANT: Download avatar FIRST, before updating userDao
                // This ensures the local file exists when the UI Flow emits.
                // If we update userDao first, the UI checks for the file before it's downloaded.
                imageDownloader.downloadUserAvatar(profile.userId, forceRefresh = true)
                logger.info { "Avatar image downloaded locally for user ${profile.userId}" }

                // Now update userDao - UI will observe this change and find the file ready
                userDao.updateAvatar(
                    userId = profile.userId,
                    avatarType = profile.avatarType,
                    avatarValue = profile.avatarValue,
                    avatarColor = profile.avatarColor,
                    updatedAt = currentEpochMilliseconds(),
                )
                logger.info { "Avatar synced to local cache: ${profile.avatarValue}" }

                Success(Unit)
            }

            is Failure -> {
                result
            }
        }
    }
}

/**
 * Handler for MARK_COMPLETE operations.
 * Coalesces by book - only the latest timestamps matter.
 * Retries failed markComplete calls so ProgressPuller doesn't overwrite local isFinished.
 */
class MarkCompleteHandler(
    private val api: SyncApiContract,
) : OperationHandler<MarkCompletePayload> {
    override val operationType = OperationType.MARK_COMPLETE

    override fun parsePayload(json: String): MarkCompletePayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: MarkCompletePayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: MarkCompletePayload,
        newPayload: MarkCompletePayload,
    ): MarkCompletePayload? {
        if (existing.operationType != OperationType.MARK_COMPLETE) return null
        return newPayload // Latest timestamps win
    }

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: MarkCompletePayload,
    ): AppResult<Unit> =
        when (
            val result =
                api.markComplete(
                    payload.bookId,
                    startedAt = payload.startedAt,
                    finishedAt = payload.finishedAt,
                )
        ) {
            is Success -> Success(Unit)
            is Failure -> result
        }
}

/**
 * Handler for DISCARD_PROGRESS operations.
 * Coalesces by book - latest discard wins (server endpoint is idempotent).
 */
class DiscardProgressHandler(
    private val api: SyncApiContract,
) : OperationHandler<DiscardProgressPayload> {
    override val operationType = OperationType.DISCARD_PROGRESS

    override fun parsePayload(json: String): DiscardProgressPayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: DiscardProgressPayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: DiscardProgressPayload,
        newPayload: DiscardProgressPayload,
    ): DiscardProgressPayload? {
        if (existing.operationType != OperationType.DISCARD_PROGRESS) return null
        return if (existingPayload.bookId == newPayload.bookId) newPayload else null
    }

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: DiscardProgressPayload,
    ): AppResult<Unit> =
        when (val result = api.discardProgress(payload.bookId, keepHistory = true)) {
            is Success -> Success(Unit)
            is Failure -> result
        }
}

/**
 * Handler for RESTART_BOOK operations.
 * Coalesces by book - latest restart wins (server endpoint is idempotent).
 * Discards the server response; the local position row was already reset by
 * PlaybackPositionRepositoryImpl.handleRestart inside the canonical transaction.
 */
class RestartBookHandler(
    private val api: SyncApiContract,
) : OperationHandler<RestartBookPayload> {
    override val operationType = OperationType.RESTART_BOOK

    override fun parsePayload(json: String): RestartBookPayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: RestartBookPayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: RestartBookPayload,
        newPayload: RestartBookPayload,
    ): RestartBookPayload? {
        if (existing.operationType != OperationType.RESTART_BOOK) return null
        return if (existingPayload.bookId == newPayload.bookId) newPayload else null
    }

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: RestartBookPayload,
    ): AppResult<Unit> =
        when (val result = api.restartBook(payload.bookId)) {
            is Success -> Success(Unit)
            is Failure -> result
        }
}

/**
 * Handler for END_PLAYBACK_SESSION operations.
 * Coalesces by book - latest duration wins for the same book.
 */
class EndPlaybackSessionHandler(
    private val api: SyncApiContract,
) : OperationHandler<EndPlaybackSessionPayload> {
    override val operationType = OperationType.END_PLAYBACK_SESSION

    override fun parsePayload(json: String): EndPlaybackSessionPayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: EndPlaybackSessionPayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: EndPlaybackSessionPayload,
        newPayload: EndPlaybackSessionPayload,
    ): EndPlaybackSessionPayload? {
        if (existing.operationType != OperationType.END_PLAYBACK_SESSION) return null
        return if (existingPayload.bookId == newPayload.bookId) newPayload else null
    }

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: EndPlaybackSessionPayload,
    ): AppResult<Unit> =
        when (val result = api.endPlaybackSession(payload.bookId, payload.durationMs)) {
            is Success -> Success(Unit)
            is Failure -> result
        }
}

/**
 * Handler for CREATE_SHELF operations.
 * Never coalesces — each create is a discrete intent.
 */
class CreateShelfHandler(
    private val api: ShelfApiContract,
) : OperationHandler<CreateShelfPayload> {
    override val operationType = OperationType.CREATE_SHELF

    override fun parsePayload(json: String): CreateShelfPayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: CreateShelfPayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: CreateShelfPayload,
        newPayload: CreateShelfPayload,
    ): CreateShelfPayload? = null // Create operations never coalesce

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: CreateShelfPayload,
    ): AppResult<Unit> =
        when (val result = api.createShelf(payload.name, payload.description)) {
            is Success -> Success(Unit)
            is Failure -> {
                logger.warn { "CreateShelfHandler failed for localId=${payload.localId}: ${result.error.message}" }
                result
            }
        }
}

/**
 * Handler for UPDATE_SHELF operations.
 * Never coalesces — each update carries the full desired state.
 */
class UpdateShelfHandler(
    private val api: ShelfApiContract,
) : OperationHandler<UpdateShelfPayload> {
    override val operationType = OperationType.UPDATE_SHELF

    override fun parsePayload(json: String): UpdateShelfPayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: UpdateShelfPayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: UpdateShelfPayload,
        newPayload: UpdateShelfPayload,
    ): UpdateShelfPayload? = null // Update operations never coalesce

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: UpdateShelfPayload,
    ): AppResult<Unit> =
        when (val result = api.updateShelf(payload.shelfId, payload.name, payload.description)) {
            is Success -> Success(Unit)
            is Failure -> {
                logger.warn { "UpdateShelfHandler failed for shelfId=${payload.shelfId}: ${result.error.message}" }
                result
            }
        }
}

/**
 * Handler for DELETE_SHELF operations.
 * Never coalesces — each delete is a discrete intent.
 */
class DeleteShelfHandler(
    private val api: ShelfApiContract,
) : OperationHandler<DeleteShelfPayload> {
    override val operationType = OperationType.DELETE_SHELF

    override fun parsePayload(json: String): DeleteShelfPayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: DeleteShelfPayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: DeleteShelfPayload,
        newPayload: DeleteShelfPayload,
    ): DeleteShelfPayload? = null // Delete operations never coalesce

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: DeleteShelfPayload,
    ): AppResult<Unit> =
        when (val result = api.deleteShelf(payload.shelfId)) {
            is Success -> result
            is Failure -> {
                logger.warn { "DeleteShelfHandler failed for shelfId=${payload.shelfId}: ${result.error.message}" }
                result
            }
        }
}

/**
 * Handler for ADD_BOOKS_TO_SHELF operations.
 * Never coalesces — each call is a discrete add intent.
 */
class AddBooksToShelfHandler(
    private val api: ShelfApiContract,
) : OperationHandler<AddBooksToShelfPayload> {
    override val operationType = OperationType.ADD_BOOKS_TO_SHELF

    override fun parsePayload(json: String): AddBooksToShelfPayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: AddBooksToShelfPayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: AddBooksToShelfPayload,
        newPayload: AddBooksToShelfPayload,
    ): AddBooksToShelfPayload? = null // Add operations never coalesce

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: AddBooksToShelfPayload,
    ): AppResult<Unit> =
        when (val result = api.addBooks(payload.shelfId, payload.bookIds)) {
            is Success -> result
            is Failure -> {
                logger.warn { "AddBooksToShelfHandler failed for shelfId=${payload.shelfId}: ${result.error.message}" }
                result
            }
        }
}

/**
 * Handler for REMOVE_BOOK_FROM_SHELF operations.
 * Never coalesces — each remove is a discrete intent.
 */
class RemoveBookFromShelfHandler(
    private val api: ShelfApiContract,
) : OperationHandler<RemoveBookFromShelfPayload> {
    override val operationType = OperationType.REMOVE_BOOK_FROM_SHELF

    override fun parsePayload(json: String): RemoveBookFromShelfPayload = appJson.decodeFromString(json)

    override fun serializePayload(payload: RemoveBookFromShelfPayload): String = appJson.encodeToString(payload)

    override fun tryCoalesce(
        existing: PendingOperationEntity,
        existingPayload: RemoveBookFromShelfPayload,
        newPayload: RemoveBookFromShelfPayload,
    ): RemoveBookFromShelfPayload? = null // Remove operations never coalesce

    override suspend fun execute(
        operation: PendingOperationEntity,
        payload: RemoveBookFromShelfPayload,
    ): AppResult<Unit> =
        when (val result = api.removeBook(payload.shelfId, payload.bookId)) {
            is Success -> result
            is Failure -> {
                logger.warn { "RemoveBookFromShelfHandler failed for shelfId=${payload.shelfId}, bookId=${payload.bookId}: ${result.error.message}" }
                result
            }
        }
}
