package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.server.metadata.ComposedBook
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BuildMatchProvenanceTest :
    FunSpec({
        fun composed(
            fieldProviders: Map<BookField, MetadataProviderId>,
            coverMax: MetadataProviderId?,
        ) = ComposedBook(
            asin = "B1",
            core = BookCoreMeta(null, null, null, null, null, null, null, null, null, emptyList(), emptyList()),
            coverUrl = null,
            coverUrlMaxSize = null,
            genres = emptyList(),
            series = emptyList(),
            fieldProviders = fieldProviders,
            coverMaxSizeWinner = coverMax,
        )

        test("fallbackFields holds only non-primary, non-cover fields; cover + footer are populated") {
            val prov =
                buildMatchProvenance(
                    composed(
                        fieldProviders =
                            mapOf(
                                BookField.TITLE to MetadataProviderId.AUDIBLE, // primary → no chip
                                BookField.DESCRIPTION to MetadataProviderId.AUDNEXUS, // fallback → chip
                                BookField.NARRATORS to MetadataProviderId.AUDNEXUS, // primary (Audnexus) → no chip
                                BookField.COVER to MetadataProviderId.AUDIBLE, // excluded (cover handled separately)
                            ),
                        coverMax = MetadataProviderId.ITUNES,
                    ),
                    routes = EnrichmentRoutes.DEFAULT,
                    coverDimensions = 3000 to 3000,
                )

            prov.fallbackFields shouldBe mapOf(BookField.DESCRIPTION to "Audnexus")
            prov.coverSource shouldBe "iTunes"
            prov.coverWidth shouldBe 3000
            prov.coverHeight shouldBe 3000
            prov.contributingSources shouldBe listOf("Audible", "Audnexus", "iTunes")
        }

        test("no cover winner and no probe dims → null cover fields") {
            val prov = buildMatchProvenance(composed(emptyMap(), coverMax = null), EnrichmentRoutes.DEFAULT, null)
            prov.coverSource shouldBe null
            prov.coverWidth shouldBe null
            prov.contributingSources shouldBe emptyList()
        }
    })
