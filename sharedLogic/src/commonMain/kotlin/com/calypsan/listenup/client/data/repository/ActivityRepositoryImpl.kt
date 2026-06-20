package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.activity.ActivityEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.mapSuspend
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.local.db.ActivityDao
import com.calypsan.listenup.client.data.local.db.ActivityEntity
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookSummary
import com.calypsan.listenup.client.data.remote.ActivityRpcFactory
import com.calypsan.listenup.client.domain.model.Activity
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import com.calypsan.listenup.client.presentation.profile.stableAvatarColorHex
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val logger = KotlinLogging.logger {}

/**
 * Activity-feed repository — Room-backed reads, [com.calypsan.listenup.api.ActivityService]
 * RPC-driven cache fill (offline-first).
 *
 * The local `activities` Room cache is the read path: [observeRecent] / [getOlderThan] /
 * [getNewestTimestamp] all serve the UI from Room and never wait on the network. The cache
 * is filled by [fetchAndCacheActivities], which pages the cross-user feed over RPC, enriches
 * each book-bearing event from the viewer's local book mirror, and upserts the result into Room.
 *
 * Book/shelf DISPLAY fields are not on the wire (the [ActivityEvent] carries only `bookId`);
 * the title/author/cover card is reconstructed from [BookDao.getBookSummary]. Identity comes
 * from the DTO's public profile projection; the avatar background colour is derived locally via
 * [stableAvatarColorHex] so it stays consistent with the rest of the app.
 *
 * @property dao Room DAO for the local activity cache (read path + upsert).
 * @property activityRpc Supplies the [com.calypsan.listenup.api.ActivityService] RPC proxy.
 * @property bookDao Local book mirror, for enriching book-bearing events with their cover card.
 */
internal class ActivityRepositoryImpl(
    private val dao: ActivityDao,
    private val activityRpc: ActivityRpcFactory,
    private val bookDao: BookDao,
) : ActivityRepository {
    // ── Read path (Room) ──────────────────────────────────────────────────────────

    override fun observeRecent(limit: Int): Flow<List<Activity>> =
        dao.observeRecent(limit).map { entities -> entities.map { it.toDomain() } }

    override suspend fun getOlderThan(
        beforeMs: Long,
        limit: Int,
    ): List<Activity> = dao.getOlderThan(beforeMs, limit).map { it.toDomain() }

    override suspend fun getNewestTimestamp(): Long? = dao.getNewestTimestamp()

    override suspend fun count(): Int = dao.count()

    override suspend fun upsertAll(activities: List<Activity>) {
        dao.upsertAll(activities.map { it.toEntity() })
    }

    // ── Cache fill (RPC → Room) ───────────────────────────────────────────────────

    override suspend fun fetchAndCacheActivities(limit: Int): AppResult<Int> =
        rpcCall { activityRpc.get().feed(before = null, limit = limit) }
            .mapSuspend { events ->
                val activities = events.map { it.toDomain() }
                dao.upsertAll(activities.map { it.toEntity() })
                logger.info { "Fetched and cached ${activities.size} activities" }
                activities.size
            }

    /**
     * Enrich a wire [ActivityEvent] into a domain [Activity]. The book card — present only for
     * book-bearing types — is reconstructed from the viewer's local book mirror; identity is taken
     * from the DTO's public-profile projection, with the avatar colour derived locally.
     */
    private suspend fun ActivityEvent.toDomain(): Activity {
        val summary = bookId?.let { bookDao.getBookSummary(it) }
        return Activity(
            id = id,
            type = type,
            userId = userId,
            occurredAtMs = occurredAtMs,
            user =
                Activity.ActivityUser(
                    displayName = displayName,
                    avatarColor = stableAvatarColorHex(userId),
                    avatarType = avatarType,
                    avatarValue = null,
                ),
            book = summary?.toActivityBook(),
            isReread = isReread,
            durationMs = durationMs,
            milestoneValue = milestoneValue,
            milestoneUnit = milestoneUnit,
            shelfId = shelfId,
            shelfName = shelfName,
        )
    }

    /**
     * Run an RPC call and surface the typed [AppResult] directly. Re-throws
     * [CancellationException]; any other throwable becomes [AppResult.Failure] via [ErrorMapper].
     */
    private suspend fun <T> rpcCall(block: suspend () -> AppResult<T>): AppResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Activity RPC failed" }
            AppResult.Failure(ErrorMapper.map(e))
        }
}

/**
 * Build the activity book card from a local [BookSummary]. The cover blur hash stands in for the
 * cover path — it is what the local mirror carries and what the feed UI renders as a placeholder.
 */
private fun BookSummary.toActivityBook(): Activity.ActivityBook =
    Activity.ActivityBook(
        id = id,
        title = title,
        authorName = authorName,
        coverPath = coverBlurHash,
    )

/**
 * Convert ActivityEntity to Activity domain model.
 */
private fun ActivityEntity.toDomain(): Activity =
    Activity(
        id = id,
        type = type,
        userId = userId,
        occurredAtMs = occurredAt,
        user =
            Activity.ActivityUser(
                displayName = userDisplayName,
                avatarColor = userAvatarColor,
                avatarType = userAvatarType,
                avatarValue = userAvatarValue,
            ),
        book =
            if (bookId != null && bookTitle != null) {
                Activity.ActivityBook(
                    id = bookId,
                    title = bookTitle,
                    authorName = bookAuthorName,
                    coverPath = bookCoverPath,
                )
            } else {
                null
            },
        isReread = isReread,
        durationMs = durationMs,
        milestoneValue = milestoneValue,
        milestoneUnit = milestoneUnit,
        shelfId = shelfId,
        shelfName = shelfName,
    )

/**
 * Convert Activity domain model to ActivityEntity for persistence.
 */
private fun Activity.toEntity(): ActivityEntity =
    ActivityEntity(
        id = id,
        userId = userId,
        type = type,
        occurredAt = occurredAtMs,
        userDisplayName = user.displayName,
        userAvatarColor = user.avatarColor,
        userAvatarType = user.avatarType,
        userAvatarValue = user.avatarValue,
        bookId = book?.id,
        bookTitle = book?.title,
        bookAuthorName = book?.authorName,
        bookCoverPath = book?.coverPath,
        isReread = isReread,
        durationMs = durationMs,
        milestoneValue = milestoneValue,
        milestoneUnit = milestoneUnit,
        shelfId = shelfId,
        shelfName = shelfName,
    )
