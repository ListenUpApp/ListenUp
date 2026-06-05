@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.remote.SocialRpcFactory
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart

private val logger = KotlinLogging.logger {}

/**
 * Book-readers repository backed by the [com.calypsan.listenup.api.SocialService] RPC.
 *
 * The server's `bookReaders` result is already ACL-filtered and excludes the caller, so this
 * repository maps the wire list straight to [Reader] — no Room, no auth lookup. It fetches on
 * first subscribe and re-fetches on every [PresenceRefreshSignal] ping (the server's presence
 * nudge or a firehose reconnect).
 *
 * On any RPC failure the flow emits empty [BookReaders] — Never-Stranded: the section renders
 * empty rather than hanging, and the next ping recovers.
 *
 * @property socialRpc Supplies the [com.calypsan.listenup.api.SocialService] RPC proxy.
 * @property presence Pings whenever presence may have changed, driving a re-fetch.
 */
class BookReadersRepositoryImpl(
    private val socialRpc: SocialRpcFactory,
    private val presence: PresenceRefreshSignal,
) : BookReadersRepository {
    override fun observeReadersFor(bookId: String): Flow<BookReaders> =
        presence.signal
            .onStart { emit(Unit) }
            .flatMapLatest { flow { emit(fetchReaders(bookId)) } }

    private suspend fun fetchReaders(bookId: String): BookReaders =
        when (val result = bookReaders(bookId)) {
            is AppResult.Success -> {
                BookReaders(
                    currentlyListening = result.data.map { Reader(userId = it.userId, displayName = it.displayName) },
                )
            }

            is AppResult.Failure -> {
                BookReaders(currentlyListening = emptyList())
            }
        }

    private suspend fun bookReaders(bookId: String) =
        try {
            socialRpc.get().bookReaders(BookId(bookId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "bookReaders RPC failed" }
            AppResult.Failure(ErrorMapper.map(e))
        }
}
