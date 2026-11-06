package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.LibraryShowcase
import com.calypsan.listenup.client.domain.repository.HomeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [HomeRepository]; tests write the flows directly. [showcase] answers every [observeLibraryShowcase];
 * continue-listening reads start empty and [getContinueListening] answers a success over [continueListeningBooks].
 */
internal class FakeHomeRepository(
    val showcase: MutableStateFlow<LibraryShowcase> = MutableStateFlow(LibraryShowcase(covers = emptyList())),
    val continueListening: MutableStateFlow<List<ContinueListeningItem>> = MutableStateFlow(emptyList()),
    var continueListeningBooks: List<ContinueListeningBook> = emptyList(),
) : HomeRepository {
    override suspend fun getContinueListening(limit: Int): AppResult<List<ContinueListeningBook>> =
        AppResult.Success(continueListeningBooks.take(limit))

    override fun observeContinueListening(limit: Int): Flow<List<ContinueListeningItem>> = continueListening

    override fun observeLibraryShowcase(coverLimit: Int): Flow<LibraryShowcase> = showcase
}
