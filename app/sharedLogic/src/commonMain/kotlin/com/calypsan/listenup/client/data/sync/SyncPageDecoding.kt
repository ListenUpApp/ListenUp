package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncPage
import kotlinx.serialization.KSerializer

/**
 * Decodes a wire [SyncPage] into the typed [Page] the domain handlers consume.
 *
 * This is the client half of the sync wire's one deliberate asymmetry: the envelope crosses
 * typed, the rows cross as encoded strings, and [serializer] — the handler's own concrete
 * payload serializer, the *same* commonMain `@Serializable` class the server encoded with —
 * turns them back into values. A renamed field still breaks both sides at compile time; only
 * the domain→type association is dynamic, and `SyncDomainRoundTripSpec` pins that.
 *
 * Decoding row-by-row (rather than the whole page in one pass) is what keeps first sync from
 * holding a page-sized JSON tree alongside the decoded rows — the shape that used to drive
 * memory spikes on a large initial library.
 */
internal fun <T : Any> SyncPage.toPage(serializer: KSerializer<T>): Page<T> =
    Page(
        items = items.map { contractJson.decodeFromString(serializer, it) },
        nextCursor = nextCursor,
        hasMore = hasMore,
    )
