package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.domain.model.AdminEvent
import com.calypsan.listenup.client.domain.model.BookEvent
import com.calypsan.listenup.client.domain.repository.EventStreamRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Empty event stream until the non-sync admin/book event domains join the RPC firehose. */
internal class EventStreamRepositoryImpl : EventStreamRepository {
    override val adminEvents: Flow<AdminEvent> = emptyFlow()
    override val bookEvents: Flow<BookEvent> = emptyFlow()
}
