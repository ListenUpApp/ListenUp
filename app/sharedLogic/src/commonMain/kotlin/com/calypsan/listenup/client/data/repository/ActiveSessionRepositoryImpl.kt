@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.dto.social.CurrentlyListeningSession
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.stableAvatarColorHex
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookSummary
import com.calypsan.listenup.client.data.local.db.CachedActiveSessionDao
import com.calypsan.listenup.client.data.local.db.CachedActiveSessionEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.data.sync.refreshTriggers
import com.calypsan.listenup.client.domain.model.ActiveSession
import com.calypsan.listenup.client.domain.repository.ActiveSessionRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private val logger = KotlinLogging.logger {}

/**
 * Offline-first active-session ("What Others Are Listening To") repository. Room's
 * `cached_active_sessions` mirror is the single read source, so the presence surface renders the
 * last-known roster (possibly stale) when offline or on a transient RPC failure — Never-Stranded, no
 * blank flash. A background refresh re-fetches the ACL-filtered
 * [com.calypsan.listenup.api.SocialService] `currentlyListening` RPC on first subscribe and on every
 * [PresenceRefreshSignal] ping, replacing the cache wholesale; on failure the cache is left untouched.
 *
 * Book identity is enriched at read time from the viewer's local Room library (which holds exactly the
 * books they can access); sessions whose book is absent locally are dropped. Because presence is
 * time-sensitive, each cached row keeps an `observedAt` for a UI staleness affordance. The avatar
 * background colour is derived from the user id via [stableAvatarColorHex]; the wire DTO carries none.
 *
 * @property channel Dispatches the [com.calypsan.listenup.api.SocialService] presence RPC.
 * @property bookDao Local library reads for enriching each session's book fields.
 * @property imageStorage Resolves the local cover path when a cover is cached.
 * @property presence Pings whenever presence may have changed, driving a background refresh.
 * @property cachedSessionDao The Room mirror — the offline read source.
 * @property clock Supplies `observedAt` for each cached row; injected for tests.
 */
internal class ActiveSessionRepositoryImpl(
    private val channel: RpcChannel<SocialService>,
    private val bookDao: BookDao,
    private val imageStorage: ImageStorage,
    private val presence: PresenceRefreshSignal,
    private val cachedSessionDao: CachedActiveSessionDao,
    private val clock: Clock = Clock.System,
) : ActiveSessionRepository {
    override fun observeActiveSessions(currentUserId: String): Flow<List<ActiveSession>> =
        merge(
            refreshOnPing(),
            cachedSessions(),
        )

    override fun observeActiveCount(currentUserId: String): Flow<Int> =
        observeActiveSessions(currentUserId).map { it.size }

    private fun cachedSessions(): Flow<List<ActiveSession>> =
        cachedSessionDao.observeAll().map { rows -> rows.mapNotNull { it.toDomainOrNull() } }

    private fun refreshOnPing(): Flow<List<ActiveSession>> =
        presence
            .refreshTriggers()
            .transform { refresh() } // never emits — the Room read carries the data

    /** Re-fetch the presence roster and replace the cache; leave it intact on failure. */
    private suspend fun refresh() {
        try {
            when (val result = channel.call(idempotent = true) { it.currentlyListening() }) {
                is AppResult.Success -> {
                    val observedAt = clock.now().toEpochMilliseconds()
                    cachedSessionDao.replaceAll(result.data.map { it.toEntity(observedAt) })
                }

                is AppResult.Failure -> {
                    logger.warn { "currently-listening refresh failed (${result.error.code}); keeping cache" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Never-Stranded: a storage fault during the cache write must not clear the cache or kill the flow.
            logger.warn(e) { "currently-listening refresh failed; keeping cache" }
        }
    }

    private suspend fun CachedActiveSessionEntity.toDomainOrNull(): ActiveSession? {
        val summary = bookDao.getBookSummary(bookId) ?: return null
        return toDomain(summary)
    }

    private fun CachedActiveSessionEntity.toDomain(summary: BookSummary): ActiveSession {
        val coverPath = imageStorage.takeIf { it.exists(BookId(bookId)) }?.getCoverPath(BookId(bookId))
        return ActiveSession(
            sessionId = "$userId:$bookId",
            userId = userId,
            bookId = bookId,
            startedAtMs = startedAtMs,
            updatedAtMs = startedAtMs,
            user =
                ActiveSession.SessionUser(
                    displayName = displayName,
                    avatarType = avatarType,
                    avatarValue = null,
                    avatarColor = stableAvatarColorHex(userId),
                ),
            book =
                ActiveSession.SessionBook(
                    id = summary.id,
                    title = summary.title,
                    coverPath = coverPath,
                    coverHash = summary.coverHash,
                    authorName = summary.authorName,
                ),
        )
    }
}

private fun CurrentlyListeningSession.toEntity(observedAt: Long): CachedActiveSessionEntity =
    CachedActiveSessionEntity(
        userId = userId,
        displayName = displayName,
        avatarType = avatarType,
        bookId = bookId,
        startedAtMs = startedAtMs,
        observedAt = observedAt,
    )
