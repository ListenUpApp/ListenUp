package com.calypsan.listenup.server.metadata.spi

import com.calypsan.listenup.api.dto.match.MetadataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MetadataProviderIdLabelTest :
    FunSpec({
        test("built-in ids map to branded labels") {
            MetadataProviderId.AUDIBLE.displayLabel() shouldBe "Audible"
            MetadataProviderId.ITUNES.displayLabel() shouldBe "iTunes"
            MetadataProviderId.AUDNEXUS.displayLabel() shouldBe "Audnexus"
        }
        test("custom ids title-case their name") {
            MetadataProviderId.custom("my source").displayLabel() shouldBe "My Source"
            MetadataProviderId.custom("openlibrary").displayLabel() shouldBe "Openlibrary"
        }
        test("Audnexus is presented as Audible, and a source carries its id and label") {
            MetadataProviderId.AUDNEXUS.toMetadataSource() shouldBe MetadataSource("audible", "Audible")
            MetadataProviderId.HARDCOVER.toMetadataSource() shouldBe MetadataSource("hardcover", "Hardcover")
            MetadataProviderId.custom("google books").toMetadataSource() shouldBe
                MetadataSource("custom:google books", "Google Books")
        }
    })
