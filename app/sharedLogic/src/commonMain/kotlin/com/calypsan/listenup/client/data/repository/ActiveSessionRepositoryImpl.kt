@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.dto.social.CurrentlyListeningSession
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.TypedAppErrorException
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.core.stableAvatarColorHex
import com.calypsan.listenup.client.data.local.db.CachedActiveSessionDao
import com.calypsan.listenup.client.data.local.db.CachedActiveSessionEntity
import com.calypsan.listenup.client.data.local.db.CachedActiveSessionWithBook
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/**
 * Offline-first "What Others Are Listening To" repository. Room's `cached_active_sessions` mirror is
 * the single read source, so the section renders the last-known roster (possibly stale) when offline
 * or on a transient RPC failure — Never-Stranded, no blank flash. The roster carries live listeners
 * first and then each other person's most recently played book; the split and the ordering are the
 * server's decision, mirrored by the DAO's `ORDER BY isLive DESC, lastActiveAtMs DESC` so a cached
 * read and a fresh one agree. A background refresh re-fetches the ACL-filtered
 * [com.calypsan.listenup.api.SocialService] `currentlyListening` RPC on first subscribe and on every
 * [PresenceRefreshSignal] ping, replacing the cache wholesale; on failure the cache is left untouched.
 *
 * Book identity is joined at read time from the viewer's local Room library (which holds exactly the
 * books they can access) in the same query as the roster; sessions whose book is absent locally are
 * dropped, and appear as soon as their book syncs in. Because presence is
 * time-sensitive, each cached row keeps an `observedAt` for a UI staleness affordance. The avatar
 * background colour is derived from the user id via [stableAvatarColorHex]; the wire DTO carries none.
 *
 * @property channel Dispatches the [com.calypsan.listenup.api.SocialService] presence RPC.
 * @property imageStorage Resolves the local cover path when a cover is cached.
 * @property presence Pings whenever presence may have changed, driving a background refresh.
 * @property cachedSessionDao The Room mirror — the offline read source.
 * @property clock Supplies `observedAt` for each cached row; injected for tests.
 */
internal class ActiveSessionRepositoryImpl(
    private val channel: RpcChannel<SocialService>,
    private val imageStorage: ImageStorage,
    private val presence: PresenceRefreshSignal,
    private val cachedSessionDao: CachedActiveSessionDao,
    private val clock: Clock = Clock.System,
) : ActiveSessionRepository {
    /**
     * The roster, from the Room mirror. A non-empty mirror (the last-known roster) flows at once, whatever the
     * fetches do: last-known rows beat an error. An empty mirror means "not fetched yet", not "nobody", so it is
     * held back while the first fetch is in flight; if that fetch fails, the flow fails with its typed
     * [AppError] (as a [TypedAppErrorException]) so the screen can say why and offer Try again, which re-runs it.
     * Once a fetch has landed, a later failure keeps what it found.
     */
    override fun observeActiveSessions(currentUserId: String): Flow<List<ActiveSession>> =
        channelFlow {
            val outcome = MutableStateFlow<FetchOutcome>(FetchOutcome.InFlight)
            launch {
                presence.refreshTriggers().collect {
                    val result = refresh()
                    outcome.update { current ->
                        when {
                            result is AppResult.Success -> FetchOutcome.Landed
                            current == FetchOutcome.Landed -> current
                            else -> FetchOutcome.Failed((result as AppResult.Failure).error)
                        }
                    }
                }
            }
            combine(cachedSessions(), outcome) { rows, fetch -> rows to fetch }.collect { (rows, fetch) ->
                when {
                    rows.isNotEmpty() || fetch == FetchOutcome.Landed -> send(rows)
                    fetch is FetchOutcome.Failed -> throw TypedAppErrorException(fetch.error)
                    else -> Unit // first fetch in flight: claim nothing yet
                }
            }
        }

    /** Where the first fetch of a subscription stands. */
    private sealed interface FetchOutcome {
        /** The fetch has not answered yet, so an empty mirror means "not fetched", not "nobody". */
        data object InFlight : FetchOutcome

        /** The fetch replaced the mirror, so an empty mirror now really means nobody is listening. */
        data object Landed : FetchOutcome

        /** The fetch failed with [error]; with an empty mirror the section shows the failure, not "nobody". */
        data class Failed(
            val error: AppError,
        ) : FetchOutcome
    }

    override fun observeActiveCount(currentUserId: String): Flow<Int> =
        observeActiveSessions(currentUserId).map { it.size }

    private fun cachedSessions(): Flow<List<ActiveSession>> =
        cachedSessionDao.observeWithBooks().map { rows -> rows.map { it.toDomain() } }

    /** Re-fetch the presence roster and replace the cache; leave it intact on failure. */
    private suspend fun refresh(): AppResult<Unit> =
        try {
            when (val result = channel.call(idempotent = true) { it.currentlyListening() }) {
                is AppResult.Success -> {
                    val observedAt = clock.now().toEpochMilliseconds()
                    cachedSessionDao.replaceAll(result.data.map { it.toEntity(observedAt) })
                    AppResult.Success(Unit)
                }

                is AppResult.Failure -> {
                    logger.warn { "currently-listening refresh failed (${result.error.code}); keeping cache" }
                    result
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Never-Stranded: a storage fault during the cache write must not clear the cache or kill the flow.
            logger.warn(e) { "currently-listening refresh failed; keeping cache" }
            AppResult.Failure(ErrorMapper.map(e))
        }

    private fun CachedActiveSessionWithBook.toDomain(): ActiveSession {
        val bookId = session.bookId
        val userId = session.userId
        val coverPath = imageStorage.takeIf { it.exists(BookId(bookId)) }?.getCoverPath(BookId(bookId))
        return ActiveSession(
            sessionId = "$userId:$bookId",
            userId = userId,
            bookId = bookId,
            lastActiveAtMs = session.lastActiveAtMs,
            isLive = session.isLive,
            observedAtMs = session.observedAt,
            user =
                ActiveSession.SessionUser(
                    displayName = session.displayName,
                    avatarType = session.avatarType,
                    avatarValue = null,
                    avatarColor = stableAvatarColorHex(userId),
                ),
            book =
                ActiveSession.SessionBook(
                    id = bookId,
                    title = bookTitle,
                    coverPath = coverPath,
                    coverHash = bookCoverHash,
                    authorName = bookAuthorName,
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
        lastActiveAtMs = lastActiveAtMs,
        isLive = isLive,
        observedAt = observedAt,
    )
