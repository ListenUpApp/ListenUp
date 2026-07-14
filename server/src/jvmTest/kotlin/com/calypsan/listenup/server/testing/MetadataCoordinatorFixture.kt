package com.calypsan.listenup.server.testing

import com.calypsan.listenup.server.metadata.EnrichmentCoordinator
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.provider.AudibleProvider
import com.calypsan.listenup.server.metadata.provider.ITunesProvider
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.services.MetadataService

/**
 * Builds an [EnrichmentCoordinator] over the real capability providers for lookup/apply tests.
 *
 * Registers [AudibleProvider] (backed by the test [metadataService]) and, when an [itunes] API is
 * supplied, [ITunesProvider] — so cover composition can be exercised. Uses the code-default
 * [EnrichmentRoutes] (the shipped provider precedence), which is what production runs without env
 * overrides.
 */
internal fun testCoordinator(
    metadataService: MetadataService,
    itunes: ITunesApi? = null,
): EnrichmentCoordinator =
    EnrichmentCoordinator(
        registry =
            MetadataProviderRegistry(
                providers =
                    buildList {
                        add(AudibleProvider(metadataService))
                        if (itunes != null) add(ITunesProvider(itunes))
                    },
            ),
        routes = EnrichmentRoutes.DEFAULT,
    )
