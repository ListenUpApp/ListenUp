package com.calypsan.listenup.server.metadata.audible

import com.calypsan.listenup.api.metadata.MetadataLocale
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the neutral-locale → Audible-region mapping at the provider edge: the default locale
 * resolves to [AudibleRegion.US], every known market code maps to its storefront, and an
 * unrecognized region falls back to US (never-strand) rather than throwing.
 */
class AudibleRegionMappingTest :
    FunSpec({
        test("the default locale maps to the US storefront") {
            MetadataLocale.DEFAULT.toAudibleRegion() shouldBe AudibleRegion.US
            MetadataLocale("us").toAudibleRegion() shouldBe AudibleRegion.US
        }

        test("every Audible region code round-trips through MetadataLocale") {
            for (region in AudibleRegion.entries) {
                MetadataLocale(region.code).toAudibleRegion() shouldBe region
            }
        }

        test("an unrecognized region falls back to US") {
            MetadataLocale("zz").toAudibleRegion() shouldBe AudibleRegion.US
            MetadataLocale(region = "us", language = "en").toAudibleRegion() shouldBe AudibleRegion.US
        }
    })
