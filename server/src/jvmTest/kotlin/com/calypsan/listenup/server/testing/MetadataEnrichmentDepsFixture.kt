package com.calypsan.listenup.server.testing

import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.server.api.MetadataEnrichmentDeps
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.metadata.audible.ProductTag
import com.calypsan.listenup.server.services.BookMoodWriter
import com.calypsan.listenup.server.services.BookTagWriter
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import kotlin.time.Clock

/**
 * Builds a [MetadataEnrichmentDeps] for apply-path tests.
 *
 * Writers are backed by real repositories against the test [sql] database so any moods/tropes a
 * test's [productTagSource] surfaces are actually persisted to `book_moods` / `book_tags` and can
 * be asserted. The default [productTagSource] returns an empty list, so tests that don't care
 * about enrichment get a no-op (the writers are never invoked) for free.
 *
 * @param productTagSource override to drive enrichment — return known typed tags to exercise
 *   the happy path, or `throw` to verify the best-effort boundary.
 */
internal fun testEnrichmentDeps(
    sql: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
    productTagSource: suspend (AudibleRegion, String) -> List<ProductTag> = { _, _ -> emptyList() },
): MetadataEnrichmentDeps =
    MetadataEnrichmentDeps(
        bookMoodWriter =
            BookMoodWriter(
                clock = clock,
                moodRepository = MoodRepository(sql, bus, registry),
                bookMoodRepository = BookMoodRepository(sql, bus, registry),
            ),
        bookTagWriter =
            BookTagWriter(
                clock = clock,
                tagRepository = TagRepository(sql, bus, registry),
                bookTagRepository = BookTagRepository(sql, bus, registry),
            ),
        productTagSource = productTagSource,
    )
