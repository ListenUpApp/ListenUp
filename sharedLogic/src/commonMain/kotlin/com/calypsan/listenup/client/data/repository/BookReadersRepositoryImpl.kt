@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.dto.social.BookReaderEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookReadershipDao
import com.calypsan.listenup.client.data.local.db.BookReadershipEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.data.sync.refreshTriggers
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private val logger = KotlinLogging.logger {}

/**
 * Offline-first book-readers repository. Room's `book_readership` mirror is the single read source, so
 * the Book Detail readers section renders the last-known readership (possibly stale) when offline or on
 * a transient RPC failure — Never-Stranded, no blank flash. A background refresh re-fetches the
 * ACL-filtered [com.calypsan.listenup.api.SocialService] `bookReadership` RPC on first subscribe and on
 * every [PresenceRefreshSignal] ping, replacing the book's cached rows wholesale; on failure the cache
 * is left untouched and the next ping recovers.
 *
 * The current user is flagged via [Reader.isYou] from [UserRepository]. `finishes` timestamps are stored
 * in the mirror as a comma-joined scalar (small, always replaced together — no child table needed).
 *
 * @property channel Dispatches the [com.calypsan.listenup.api.SocialService] readership RPC.
 * @property presence Pings whenever presence may have changed, driving a background refresh.
 * @property userRepository Source of the current user's identity, used to flag [Reader.isYou].
 * @property readershipDao The Room mirror — the offline read source.
 * @property clock Supplies `observedAt` for each cached row; injected for tests.
 */
internal class BookReadersRepositoryImpl(
    private val channel: RpcChannel<SocialService>,
    private val presence: PresenceRefreshSignal,
    private val userRepository: UserRepository,
    private val readershipDao: BookReadershipDao,
    private val clock: Clock = Clock.System,
) : BookReadersRepository {
    override fun observeReadersFor(bookId: String): Flow<BookReaders> =
        merge(
            // Side-effect flow: refreshes the Room mirror on each ping; emits nothing itself.
            refreshOnPing(bookId),
            // Read source: the Room mirror joined with the current user, mapped to the domain.
            cachedReaders(bookId),
        )

    private fun cachedReaders(bookId: String): Flow<BookReaders> =
        combine(
            readershipDao.observeForBook(bookId),
            userRepository.observeCurrentUser(),
        ) { rows, currentUser ->
            val myId = currentUser?.id?.value
            BookReaders(readers = rows.map { it.toReader(myId) })
        }

    private fun refreshOnPing(bookId: String): Flow<BookReaders> =
        presence
            .refreshTriggers()
            .transform { refresh(bookId) } // never emits — the Room read carries the data

    /** Re-fetch the readership and replace the book's cached rows; leave the cache intact on failure. */
    private suspend fun refresh(bookId: String) {
        try {
            when (val result = channel.call(idempotent = true) { it.bookReadership(BookId(bookId)) }) {
                is AppResult.Success -> {
                    val observedAt = clock.now().toEpochMilliseconds()
                    readershipDao.replaceForBook(bookId, result.data.readers.map { it.toEntity(bookId, observedAt) })
                }

                is AppResult.Failure -> {
                    logger.warn { "bookReadership refresh failed (${result.error.code}); keeping cached readership" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Never-Stranded: a storage fault during refresh must not clear the cache or kill the flow.
            logger.warn(e) { "bookReadership refresh failed; keeping cached readership" }
        }
    }
}

private fun BookReaderEntry.toEntity(
    bookId: String,
    observedAt: Long,
): BookReadershipEntity =
    BookReadershipEntity(
        bookId = bookId,
        userId = userId,
        displayName = displayName,
        avatarType = avatarType,
        currentProgressPct = currentProgressPct,
        finishesJson = finishes.joinToString(","),
        observedAt = observedAt,
    )

private fun BookReadershipEntity.toReader(currentUserId: String?): Reader =
    Reader(
        userId = userId,
        displayName = displayName,
        isYou = userId == currentUserId,
        currentProgressPct = currentProgressPct,
        finishes = if (finishesJson.isEmpty()) emptyList() else finishesJson.split(",").map { it.toLong() },
    )
