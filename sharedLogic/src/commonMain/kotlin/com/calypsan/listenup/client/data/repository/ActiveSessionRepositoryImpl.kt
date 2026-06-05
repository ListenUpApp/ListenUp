@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.social.CurrentlyListeningSession
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookSummary
import com.calypsan.listenup.client.data.remote.SocialRpcFactory
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.domain.model.ActiveSession
import com.calypsan.listenup.client.domain.repository.ActiveSessionRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.presentation.profile.stableAvatarColorHex
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

private val logger = KotlinLogging.logger {}

/**
 * Active-session repository backed by the [com.calypsan.listenup.api.SocialService] RPC.
 *
 * The "What Others Are Listening To" list is ACL-filtered server-side, so this repository fetches
 * it on first subscribe and re-fetches on every [PresenceRefreshSignal] ping (the server's
 * presence nudge or a firehose reconnect). Book identity arrives as a [BookId] only; title,
 * author, blur hash, and the local cover path are enriched from the viewer's local Room library,
 * which holds exactly the books they can access. Sessions whose book is absent locally are dropped.
 *
 * On any RPC failure the flow emits an empty list — Never-Stranded: the UI shows nothing rather
 * than hanging, and the next ping recovers. The avatar background colour is derived from the user
 * id via [stableAvatarColorHex] so it matches the rest of the app; the wire DTO carries no colour.
 *
 * @property socialRpc Supplies the [com.calypsan.listenup.api.SocialService] RPC proxy.
 * @property bookDao Local library reads for enriching each session's book fields.
 * @property imageStorage Resolves the local cover path when a cover is cached.
 * @property presence Pings whenever presence may have changed, driving a re-fetch.
 */
class ActiveSessionRepositoryImpl(
    private val socialRpc: SocialRpcFactory,
    private val bookDao: BookDao,
    private val imageStorage: ImageStorage,
    private val presence: PresenceRefreshSignal,
) : ActiveSessionRepository {
    override fun observeActiveSessions(currentUserId: String): Flow<List<ActiveSession>> =
        presence.signal
            .onStart { emit(Unit) }
            .flatMapLatest { flow { emit(fetchSessions()) } }

    override fun observeActiveCount(currentUserId: String): Flow<Int> =
        observeActiveSessions(currentUserId).map { it.size }

    private suspend fun fetchSessions(): List<ActiveSession> =
        try {
            when (val result = socialRpc.get().currentlyListening()) {
                is AppResult.Success -> result.data.mapNotNull { it.toDomainOrNull() }
                is AppResult.Failure -> emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Never-Stranded: an RPC fault or a transient Room/disk error during book enrichment
            // must not terminate the flow. Emit an empty list; the next presence ping recovers.
            logger.warn(e) { "currently-listening fetch failed" }
            emptyList()
        }

    private suspend fun CurrentlyListeningSession.toDomainOrNull(): ActiveSession? {
        val summary = bookDao.getBookSummary(bookId) ?: return null
        return toDomain(summary)
    }

    private fun CurrentlyListeningSession.toDomain(summary: BookSummary): ActiveSession {
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
                    coverBlurHash = summary.coverBlurHash,
                    authorName = summary.authorName,
                ),
        )
    }
}
