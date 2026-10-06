package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.spi.BookFindSource
import com.calypsan.listenup.server.metadata.spi.FindAnswer
import com.calypsan.listenup.server.metadata.spi.FindAvailability
import com.calypsan.listenup.server.metadata.spi.FindLookup
import com.calypsan.listenup.server.metadata.spi.FindRole
import com.calypsan.listenup.server.metadata.spi.FindStep
import com.calypsan.listenup.server.metadata.spi.FoundBook
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.RegionalSource
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * A scripted Find source with in-memory state: [answer] decides each reply from the lookup, [latency] is
 * virtual time under `runTest`, and every lookup lands in [asked].
 */
internal open class FakeFindSource(
    override val id: MetadataProviderId,
    override val findRole: FindRole = FindRole.IDENTIFIES,
    var latency: Duration = Duration.ZERO,
    var availability: FindAvailability = FindAvailability.Available,
    var answer: (
        FindLookup,
    ) -> AppResult<FindAnswer> = { AppResult.Success(FindAnswer(emptyList(), setOf(FindStep.TEXT))) },
) : BookFindSource {
    val asked = mutableListOf<FindLookup>()

    override suspend fun findAvailability(): FindAvailability = availability

    override suspend fun findBooks(
        lookup: FindLookup,
        locale: MetadataLocale,
    ): AppResult<FindAnswer> {
        asked += lookup
        if (latency > Duration.ZERO) delay(latency)
        return answer(lookup)
    }

    /** Answers with [books], having run [steps]. */
    fun answers(
        books: List<FoundBook>,
        steps: Set<FindStep> = setOf(FindStep.TEXT),
    ) {
        answer = { AppResult.Success(FindAnswer(books, steps)) }
    }
}

/** A fake source with stores, like Audible. */
internal class FakeRegionalFindSource(
    id: MetadataProviderId,
) : FakeFindSource(id),
    RegionalSource {
    override fun hasStore(region: String): Boolean = region in STORES

    private companion object {
        val STORES = setOf("us", "uk", "de", "fr", "au", "ca", "jp", "it", "in", "es")
    }
}
