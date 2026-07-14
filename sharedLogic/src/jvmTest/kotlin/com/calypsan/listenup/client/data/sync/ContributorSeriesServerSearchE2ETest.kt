package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val CONTRIBUTOR_NAME = "Brandon Sanderson"
private const val SERIES_NAME = "The Stormlight Archive"

/**
 * Tier 3 e2e proving the contributor/series autocomplete "server search" actually reaches the
 * server. Before this fix, `ContributorRepositoryImpl.searchServer` (and the series equivalent)
 * called the REST `GET /api/v1/contributors/search` / `/api/v1/series/search` endpoints — which
 * the Kotlin server never exposed. Every server search 404'd, was swallowed by the never-stranded
 * fallback, and silently degraded to the local FTS path (which, against an unsynced client Room,
 * returns nothing — or at best a `bookCount` of 0).
 *
 * The repos now route through the unified [com.calypsan.listenup.api.SearchService] over RPC. This
 * test seeds a contributor + series (each linked to a book) on the in-process `:server`, then calls
 * the client repo's server search and asserts:
 *  - the seeded entity comes back (proving the RPC round trip reached the server), and
 *  - it carries a REAL `bookCount` of 1 (the server-computed junction count — impossible from the
 *    empty-Room local-FTS fallback, which would have yielded 0).
 *
 * `isOfflineResult == false` pins that the result came from the server path, not the fallback.
 */
class ContributorSeriesServerSearchE2ETest :
    FunSpec({

        test("contributor server search returns the seeded hit with a real bookCount via SearchService") {
            withClientSyncEngineAgainstServer {
                val contributorId = serverContributorRepository.resolveOrCreate(CONTRIBUTOR_NAME, sortName = null)
                serverBookRepository.upsert(
                    bookFixture(
                        id = "search-contrib-b1",
                        title = "Server Search Contributor Book",
                        contributors =
                            listOf(
                                BookContributorPayload(
                                    id = contributorId.value,
                                    name = CONTRIBUTOR_NAME,
                                    sortName = null,
                                    role = "author",
                                    creditedAs = null,
                                ),
                            ),
                    ),
                )

                val response = contributorSearchRepository.searchContributors("Sanderson")

                response.isOfflineResult shouldBe false
                val hit = response.contributors.singleOrNull { it.name == CONTRIBUTOR_NAME }
                (hit != null) shouldBe true
                // A REAL server-computed junction count — the local-FTS fallback would return 0.
                hit!!.bookCount shouldBe 1
            }
        }

        test("series server search returns the seeded hit with a real bookCount via SearchService") {
            withClientSyncEngineAgainstServer {
                val seriesId = serverSeriesRepository.resolveOrCreate(SERIES_NAME)
                serverBookRepository.upsert(
                    bookFixture(
                        id = "search-series-b1",
                        title = "Server Search Series Book",
                        series =
                            listOf(
                                BookSeriesPayload(
                                    id = seriesId.value,
                                    name = SERIES_NAME,
                                    sequence = "1",
                                ),
                            ),
                    ),
                )

                val response = seriesSearchRepository.searchSeries("Stormlight")

                response.isOfflineResult shouldBe false
                val hit = response.series.singleOrNull { it.name == SERIES_NAME }
                (hit != null) shouldBe true
                hit!!.bookCount shouldBe 1
            }
        }
    })

private fun bookFixture(
    id: String,
    title: String,
    contributors: List<BookContributorPayload> = emptyList(),
    series: List<BookSeriesPayload> = emptyList(),
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        sortTitle = title,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = 3_600_000L,
        cover = null,
        rootRelPath = "books/$id",
        inode = null,
        scannedAt = 1L,
        contributors = contributors,
        series = series,
        audioFiles = emptyList(),
        chapters = emptyList(),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
