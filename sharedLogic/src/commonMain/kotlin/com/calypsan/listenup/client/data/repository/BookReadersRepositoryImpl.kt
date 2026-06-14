@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.remote.SocialRpcFactory
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.domain.readers.ReaderState
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart

private val logger = KotlinLogging.logger {}

/**
 * Book-readers repository combining the current user's own reading state with other live listeners.
 *
 * The current user's row comes from the local playback position ([PlaybackPositionRepository]) and
 * profile ([UserRepository]) — so it shows whether *you* are reading or have finished the book even
 * when the server is unreachable. Other listeners come from the [com.calypsan.listenup.api.SocialService]
 * `bookReadership` RPC, which is ACL-filtered; that list is fetched on first subscribe and re-fetched
 * on every [PresenceRefreshSignal] ping. The readership now *includes* the caller server-side, so the
 * caller's own entry is dropped here in favour of the locally-derived self row, which is listed first.
 *
 * On any RPC failure the *other-listeners* list is empty — Never-Stranded: the section still shows
 * the current user's own row, and the next ping recovers the others.
 *
 * @property socialRpc Supplies the [com.calypsan.listenup.api.SocialService] RPC proxy.
 * @property presence Pings whenever presence may have changed, driving a re-fetch of other listeners.
 * @property playbackPositionRepository Source of the current user's local reading state per book.
 * @property userRepository Source of the current user's identity (id + display name).
 */
class BookReadersRepositoryImpl(
    private val socialRpc: SocialRpcFactory,
    private val presence: PresenceRefreshSignal,
    private val playbackPositionRepository: PlaybackPositionRepository,
    private val userRepository: UserRepository,
) : BookReadersRepository {
    override fun observeReadersFor(bookId: String): Flow<BookReaders> =
        combine(
            readership(bookId),
            playbackPositionRepository.observeAll(),
            userRepository.observeCurrentUser(),
        ) { readers, positions, currentUser ->
            val self = currentUser?.let { user -> selfReader(user, positions[BookId(bookId)]) }
            // The server-side readership now includes the caller; drop their entry in favour of the
            // locally-derived self row (authoritative and offline-resilient), listed first.
            val others = readers.filterNot { it.userId == currentUser?.id?.value }
            BookReaders(readers = listOfNotNull(self) + others)
        }

    /** The full readership of the book — ACL-filtered server-side; empty on any RPC failure. */
    private fun readership(bookId: String): Flow<List<Reader>> =
        presence.signal
            .onStart { emit(Unit) }
            .flatMapLatest { flow { emit(fetchReadership(bookId)) } }

    private suspend fun fetchReadership(bookId: String): List<Reader> =
        when (val result = bookReadership(bookId)) {
            is AppResult.Success -> {
                result.data.readers.map { entry ->
                    val state =
                        when {
                            entry.currentProgressPct != null -> ReaderState.Listening
                            else -> ReaderState.Finished(entry.finishes.firstOrNull())
                        }
                    Reader(userId = entry.userId, displayName = entry.displayName, state = state)
                }
            }

            is AppResult.Failure -> {
                emptyList()
            }
        }

    /**
     * The current user as a reader of this book, derived from their local [position] — or null when
     * they haven't started it (no position, or a zeroed position that isn't finished).
     */
    private fun selfReader(
        user: User,
        position: PlaybackPosition?,
    ): Reader? {
        if (position == null) return null
        val state =
            when {
                position.isFinished -> ReaderState.Finished(position.finishedAtMs)
                position.positionMs > 0 -> ReaderState.Listening
                else -> return null
            }
        return Reader(userId = user.id.value, displayName = user.displayName, state = state)
    }

    private suspend fun bookReadership(bookId: String) =
        try {
            socialRpc.get().bookReadership(BookId(bookId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "bookReadership RPC failed" }
            AppResult.Failure(ErrorMapper.map(e))
        }
}
