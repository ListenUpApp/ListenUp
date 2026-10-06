package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.RegionOrigin
import com.calypsan.listenup.api.metadata.MetadataLocale
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FindRegionTest :
    FunSpec({
        test("the store is this search's pick, then the library's, then the server's default") {
            resolveFindRegion(MetadataLocale("au"), "uk") shouldBe
                ResolvedRegion(MetadataLocale("au"), RegionOrigin.SEARCH_OVERRIDE)
            resolveFindRegion(null, "uk") shouldBe ResolvedRegion(MetadataLocale("uk"), RegionOrigin.LIBRARY)
            resolveFindRegion(null, null) shouldBe ResolvedRegion(MetadataLocale.DEFAULT, RegionOrigin.SERVER_DEFAULT)
            resolveFindRegion(null, " ") shouldBe ResolvedRegion(MetadataLocale.DEFAULT, RegionOrigin.SERVER_DEFAULT)
        }

        test("an English book missing from the UK store suggests the United States, then Australia") {
            suggestStores(MetadataLocale("uk"), yourCopyOfPhm(), "audible") shouldBe
                listOf(MetadataLocale("us"), MetadataLocale("au"))
        }

        test("the store of an existing link comes first, and the current store is never suggested") {
            val linkedInCanada = yourCopyOfPhm(refs = listOf(ExternalRef("audible", "B1", "ca")))
            suggestStores(MetadataLocale("us"), linkedInCanada, "audible") shouldBe
                listOf(MetadataLocale("ca"), MetadataLocale("uk"))
        }

        test("a German book suggests the United States and Germany; an unknown language just the United States") {
            suggestStores(MetadataLocale("uk"), yourCopyOfPhm(language = "de"), "audible") shouldBe
                listOf(MetadataLocale("us"), MetadataLocale("de"))
            suggestStores(MetadataLocale("uk"), yourCopyOfPhm(language = null), "audible") shouldBe
                listOf(MetadataLocale("us"))
        }
    })
