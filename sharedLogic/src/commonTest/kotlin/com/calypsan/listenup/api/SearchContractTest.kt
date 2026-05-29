package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.BookHit
import com.calypsan.listenup.api.dto.FacetBucket
import com.calypsan.listenup.api.dto.SearchFacets
import com.calypsan.listenup.api.dto.SearchFilters
import com.calypsan.listenup.api.dto.SearchQuery
import com.calypsan.listenup.api.dto.SearchResults
import com.calypsan.listenup.api.dto.SearchSort
import com.calypsan.listenup.api.dto.TypeCounts
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SearchContractTest :
    FunSpec({
        val json = Json { encodeDefaults = true }

        test("SearchQuery round-trips with filters and sort") {
            val q =
                SearchQuery(
                    text = "kings",
                    limit = 30,
                    filters = SearchFilters(genreSlugs = listOf("fantasy"), genrePath = "/fiction", yearMin = 2000),
                    sort = SearchSort.Title,
                )
            json.decodeFromString<SearchQuery>(json.encodeToString(q)) shouldBe q
        }
        test("SearchFilters.isActive reflects any constraint") {
            SearchFilters().isActive shouldBe false
            SearchFilters(genreSlugs = listOf("x")).isActive shouldBe true
            SearchFilters(genrePath = "/a").isActive shouldBe true
            SearchFilters(durationMaxSeconds = 1).isActive shouldBe true
            SearchFilters(durationMinSeconds = 1).isActive shouldBe true
            SearchFilters(yearMin = 1).isActive shouldBe true
            SearchFilters(yearMax = 1).isActive shouldBe true
        }
        test("SearchResults round-trips with facets and highlight") {
            val r =
                SearchResults(
                    books = listOf(BookHit(BookId("b1"), "Title", listOf("A"), null, null, highlight = "Title")),
                    contributors = emptyList(),
                    series = emptyList(),
                    tags = emptyList(),
                    facets = SearchFacets(types = TypeCounts(books = 1), genres = listOf(FacetBucket("fantasy", "Fantasy", 1))),
                )
            json.decodeFromString<SearchResults>(json.encodeToString(r)) shouldBe r
        }
    })
