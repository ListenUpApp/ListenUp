@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.social.BookReaderEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.remote.SocialRpcFactory
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart

private val logger = KotlinLogging.logger {}

/**
 * Book-readers repository over the [com.calypsan.listenup.api.SocialService] `bookReadership` RPC.
 *
 * The RPC is ACL-filtered and now *includes* the caller, so each [BookReaderEntry] maps straight to a
 * domain [Reader] — the current user flagged via [Reader.isYou] from [UserRepository]. The readership
 * is fetched on first subscribe and re-fetched on every [PresenceRefreshSignal] ping.
 *
 * On any RPC failure the list is empty — Never-Stranded: the section renders empty rather than
 * hanging, and the next ping recovers.
 *
 * @property socialRpc Supplies the [com.calypsan.listenup.api.SocialService] RPC proxy.
 * @property presence Pings whenever presence may have changed, driving a re-fetch of the readership.
 * @property userRepository Source of the current user's identity, used to flag [Reader.isYou].
 */
internal class BookReadersRepositoryImpl(
    private val socialRpc: SocialRpcFactory,
    private val presence: PresenceRefreshSignal,
    private val userRepository: UserRepository,
) : BookReadersRepository {
    override fun observeReadersFor(bookId: String): Flow<BookReaders> =
        combine(
            readershipFlow(bookId),
            userRepository.observeCurrentUser(),
        ) { entries, currentUser ->
            val myId = currentUser?.id?.value
            BookReaders(
                readers =
                    entries.map { entry ->
                        Reader(
                            userId = entry.userId,
                            displayName = entry.displayName,
                            isYou = entry.userId == myId,
                            currentProgressPct = entry.currentProgressPct,
                            finishes = entry.finishes,
                        )
                    },
            )
        }

    /** The full readership of the book — ACL-filtered server-side; empty on any RPC failure. */
    private fun readershipFlow(bookId: String): Flow<List<BookReaderEntry>> =
        presence.signal
            .onStart { emit(Unit) }
            .flatMapLatest { flow { emit(fetchReadership(bookId)) } }

    private suspend fun fetchReadership(bookId: String): List<BookReaderEntry> =
        when (val result = bookReadership(bookId)) {
            is AppResult.Success -> result.data.readers
            is AppResult.Failure -> emptyList()
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
