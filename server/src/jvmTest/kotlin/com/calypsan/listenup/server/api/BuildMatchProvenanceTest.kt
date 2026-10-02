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
            genreProviders: Map<String, MetadataProviderId> = emptyMap(),
            moods: List<String> = emptyList(),
        ) = ComposedBook(
            asin = "B1",
            core = BookCoreMeta(null, null, null, null, null, null, null, null, null, emptyList(), emptyList()),
            coverUrl = null,
            coverUrlMaxSize = null,
            genres = emptyList(),
            series = emptyList(),
            fieldProviders = fieldProviders,
            coverMaxSizeWinner = coverMax,
            moods = moods,
            genreProviders = genreProviders,
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

        test("Audible-only cover (no max-size winner) reports the primary cover winner + keeps probed dims") {
            val prov =
                buildMatchProvenance(
                    composed(
                        fieldProviders = mapOf(BookField.COVER to MetadataProviderId.AUDIBLE),
                        coverMax = null, // iTunes had no match → no max-size winner
                    ),
                    routes = EnrichmentRoutes.DEFAULT,
                    coverDimensions = 2400 to 2400,
                )

            prov.coverSource shouldBe "Audible"
            prov.coverWidth shouldBe 2400
            prov.coverHeight shouldBe 2400
            prov.contributingSources shouldBe listOf("Audible")
        }

        test("no cover winner and no probe dims → null cover fields") {
            val prov = buildMatchProvenance(composed(emptyMap(), coverMax = null), EnrichmentRoutes.DEFAULT, null)
            prov.coverSource shouldBe null
            prov.coverWidth shouldBe null
            prov.contributingSources shouldBe emptyList()
        }

        test("a field Hardcover supplied is labelled, even where Hardcover is that field's primary (moods)") {
            val prov =
                buildMatchProvenance(
                    composed(
                        fieldProviders =
                            mapOf(
                                BookField.TITLE to MetadataProviderId.AUDIBLE,
                                BookField.MOODS to MetadataProviderId.HARDCOVER,
                                BookField.SERIES to MetadataProviderId.HARDCOVER,
                            ),
                        coverMax = null,
                    ),
                    routes = EnrichmentRoutes.DEFAULT,
                    coverDimensions = null,
                )

            prov.fallbackFields shouldBe mapOf(BookField.MOODS to "Hardcover", BookField.SERIES to "Hardcover")
            prov.contributingSources shouldBe listOf("Audible", "Hardcover")
        }

        test("the genres Hardcover added are named one by one, and Hardcover joins the footer") {
            val prov =
                buildMatchProvenance(
                    composed(
                        fieldProviders =
                            mapOf(
                                BookField.TITLE to MetadataProviderId.AUDIBLE,
                                BookField.GENRES to MetadataProviderId.AUDIBLE,
                            ),
                        coverMax = null,
                        genreProviders = mapOf("Space Opera" to MetadataProviderId.HARDCOVER),
                    ),
                    routes = EnrichmentRoutes.DEFAULT,
                    coverDimensions = null,
                )

            prov.genreSources shouldBe mapOf("Space Opera" to "Hardcover")
            prov.fallbackFields.containsKey(BookField.GENRES) shouldBe false
            prov.contributingSources shouldBe listOf("Audible", "Hardcover")
        }

        test("the wire book carries the composed moods") {
            composed(emptyMap(), coverMax = null, moods = listOf("Hopeful", "Funny")).toMetadataBook().moods shouldBe
                listOf("Hopeful", "Funny")
        }
    })
