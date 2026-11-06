package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.model.Activity
import com.calypsan.listenup.client.domain.model.ProfileRecentBook
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [ActivityRepository]; tests write the flows directly. [finishers] answers every
 * [observeOtherFinishers]; the other two reads start empty.
 */
internal class FakeActivityRepository(
    val finishers: MutableStateFlow<List<String>> = MutableStateFlow(emptyList()),
    val recent: MutableStateFlow<List<Activity>> = MutableStateFlow(emptyList()),
    val recentlyListened: MutableStateFlow<List<ProfileRecentBook>> = MutableStateFlow(emptyList()),
) : ActivityRepository {
    override fun observeRecent(limit: Int): Flow<List<Activity>> = recent

    override fun observeRecentlyListened(
        userId: String,
        limit: Int,
    ): Flow<List<ProfileRecentBook>> = recentlyListened

    override fun observeOtherFinishers(
        bookId: String,
        excludeUserId: String,
    ): Flow<List<String>> = finishers
}
