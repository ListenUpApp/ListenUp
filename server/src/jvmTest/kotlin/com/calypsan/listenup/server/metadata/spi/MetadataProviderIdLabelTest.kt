package com.calypsan.listenup.server.metadata.spi

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
    })
