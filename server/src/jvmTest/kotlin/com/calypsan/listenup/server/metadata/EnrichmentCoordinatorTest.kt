@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.metadata

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataDomain
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.toMetadataBook
import com.calypsan.listenup.server.metadata.spi.BookContributorMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreSource
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.BookIdentitySource
import com.calypsan.listenup.server.metadata.spi.BookMatch
import com.calypsan.listenup.server.metadata.spi.ChapterListMeta
import com.calypsan.listenup.server.metadata.spi.ChapterMeta
import com.calypsan.listenup.server.metadata.spi.ChapterSource
import com.calypsan.listenup.server.metadata.spi.ContributorHitMeta
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import com.calypsan.listenup.server.metadata.spi.ContributorSource
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.CoverSource
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.GenreKind
import com.calypsan.listenup.server.metadata.spi.GenreLadderSource
import com.calypsan.listenup.server.metadata.spi.GenreMeta
import com.calypsan.listenup.server.metadata.spi.GenreSource
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import com.calypsan.listenup.server.metadata.spi.SeriesSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private val US = MetadataLocale("us")

private fun coordinator(vararg providers: FakeProvider): EnrichmentCoordinator {
    val registry = MetadataProviderRegistry(providers.toList())
    return EnrichmentCoordinator(registry, EnrichmentRoutes.DEFAULT)
}

private fun coordinator(
    routes: EnrichmentRoutes,
    vararg providers: FakeProvider,
): EnrichmentCoordinator = EnrichmentCoordinator(MetadataProviderRegistry(providers.toList()), routes)

/** Unwraps the composed book, failing the test if the compose returned a typed failure. */
private suspend fun EnrichmentCoordinator.composeBookData(
    identity: BookIdentity,
    locale: MetadataLocale,
    refresh: Boolean = false,
): ComposedBook? = (composeBook(identity, locale, refresh) as AppResult.Success).data

private fun identity(asin: String = "B01") = BookIdentity(asin = asin, title = "")

class EnrichmentCoordinatorTest :
    FunSpec({

        test("composeBook reproduces the pre-refactor MetadataBook shape (Audible core + genres + series, iTunes max cover)") {
            val audible =
                FakeProvider(
                    id = MetadataProviderId.AUDIBLE,
                    core =
                        AppResult.Success(
                            BookCoreMeta(
                                title = "The Way of Kings",
                                subtitle = "The Stormlight Archive, Book 1",
                                description = "Roshar is a world of stone and storms.",
                                publisher = "Macmillan Audio",
                                releaseDate = "2010-08-31",
                                language = "english",
                                runtimeMinutes = 2734,
                                authors =
                                    listOf(
                                        BookContributorMeta(key = "a1", name = "Brandon Sanderson", role = ContributorRole.AUTHOR),
                                    ),
                                narrators = listOf(BookContributorMeta(key = "n1", name = "Kate Reading", role = ContributorRole.NARRATOR)),
                            ),
                        ),
                    covers = AppResult.Success(listOf(CoverMeta(url = "https://audible.test/cover.jpg", sourceKey = "B01"))),
                    genres =
                        AppResult.Success(
                            listOf(GenreMeta("Fantasy", GenreKind.GENRE), GenreMeta("Epic", GenreKind.GENRE)),
                        ),
                    series = AppResult.Success(listOf(SeriesMeta(key = "S1", title = "The Stormlight Archive", sequence = "1"))),
                )
            val itunes =
                FakeProvider(
                    id = MetadataProviderId.ITUNES,
                    covers =
                        AppResult.Success(
                            listOf(
                                CoverMeta(
                                    url = "https://itunes.test/100.jpg",
                                    maxSizeUrl = "https://itunes.test/7000.jpg",
                                    sourceKey = "123",
                                ),
                            ),
                        ),
                )

            runTest {
                val book = coordinator(audible, itunes).composeBookData(identity(), US)!!.toMetadataBook()

                book.asin shouldBe "B01"
                book.title shouldBe "The Way of Kings"
                book.subtitle shouldBe "The Stormlight Archive, Book 1"
                book.description shouldBe "Roshar is a world of stone and storms."
                book.publisher shouldBe "Macmillan Audio"
                book.releaseDate shouldBe "2010-08-31"
                book.language shouldBe "english"
                book.runtimeMinutes shouldBe 2734
                book.authors.map { it.name } shouldContainExactly listOf("Brandon Sanderson")
                book.narrators.map { it.name } shouldContainExactly listOf("Kate Reading")
                book.series.map { it.title } shouldContainExactly listOf("The Stormlight Archive")
                book.genres shouldContainExactly listOf("Fantasy", "Epic")
                // Audible provides the primary cover; iTunes provides the max-resolution rendition.
                book.coverUrl shouldBe "https://audible.test/cover.jpg"
                book.coverUrlMaxSize shouldBe "https://itunes.test/7000.jpg"
                // The dead moods/tags scrape is gone — both are empty on the composed preview.
                book.moods.shouldBeEmpty()
                book.tags.shouldBeEmpty()
            }
        }

        test("cover falls back to iTunes when Audible has no cover") {
            val audible =
                FakeProvider(
                    id = MetadataProviderId.AUDIBLE,
                    core = AppResult.Success(BookCoreMeta(title = "T")),
                    covers = AppResult.Success(emptyList()),
                )
            val itunes =
                FakeProvider(
                    id = MetadataProviderId.ITUNES,
                    covers =
                        AppResult.Success(
                            listOf(
                                CoverMeta(
                                    url = "https://itunes.test/c.jpg",
                                    maxSizeUrl = "https://itunes.test/max.jpg",
                                    sourceKey = "1",
                                ),
                            ),
                        ),
                )

            runTest {
                val composed = coordinator(audible, itunes).composeBookData(identity(), US)!!
                composed.coverUrl shouldBe "https://itunes.test/c.jpg"
                composed.coverUrlMaxSize shouldBe "https://itunes.test/max.jpg"
            }
        }

        test("core fields resolve first-non-empty across the provider chain") {
            // Audible ranks first but lacks a description; Audnexus fills it in.
            val audible =
                FakeProvider(
                    id = MetadataProviderId.AUDIBLE,
                    core = AppResult.Success(BookCoreMeta(title = "From Audible", description = null)),
                )
            val audnexus =
                FakeProvider(
                    id = MetadataProviderId.AUDNEXUS,
                    core = AppResult.Success(BookCoreMeta(title = "From Audnexus", description = "Audnexus synopsis")),
                )

            runTest {
                val composed = coordinator(audible, audnexus).composeBookData(identity(), US)!!
                composed.core.title shouldBe "From Audible"
                composed.core.description shouldBe "Audnexus synopsis"
            }
        }

        test("a provider error is contained — a later provider still supplies the core") {
            val audible = FakeProvider(id = MetadataProviderId.AUDIBLE, core = AppResult.Failure(MetadataError.ExternalUnavailable()))
            val audnexus = FakeProvider(id = MetadataProviderId.AUDNEXUS, core = AppResult.Success(BookCoreMeta(title = "Survived")))

            runTest {
                coordinator(audible, audnexus).composeBookData(identity(), US)!!.core.title shouldBe "Survived"
            }
        }

        test("composeBook returns null when no provider has core metadata") {
            val audible = FakeProvider(id = MetadataProviderId.AUDIBLE, core = AppResult.Success(null))

            runTest {
                coordinator(audible).composeBookData(identity(), US).shouldBeNull()
            }
        }

        test("composeChapters prefers a catalog-verified list over an earlier heuristic one") {
            // Audnexus ranks ahead of Audible in the chapter chain but is heuristic; Audible is accurate.
            val audnexus =
                FakeProvider(
                    id = MetadataProviderId.AUDNEXUS,
                    chapters = AppResult.Success(ChapterListMeta(listOf(ChapterMeta(title = "Guess", startMs = 0)), accurate = false)),
                )
            val audible =
                FakeProvider(
                    id = MetadataProviderId.AUDIBLE,
                    chapters = AppResult.Success(ChapterListMeta(listOf(ChapterMeta(title = "Prologue", startMs = 0)), accurate = true)),
                )

            runTest {
                val chapters = coordinator(audnexus, audible).composeChapters(identity(), US)
                chapters.shouldNotBeNull()
                chapters.accurate shouldBe true
                chapters.chapters.single().title shouldBe "Prologue"
            }
        }

        test("searchBooks aggregates candidates across identity sources in book-core order") {
            val audible =
                FakeProvider(
                    id = MetadataProviderId.AUDIBLE,
                    matches = AppResult.Success(listOf(BookMatch(asin = "B1", title = "First", score = 1.0))),
                )
            val audnexus =
                FakeProvider(
                    id = MetadataProviderId.AUDNEXUS,
                    matches = AppResult.Success(listOf(BookMatch(asin = "B2", title = "Second", score = 1.0))),
                )

            runTest {
                coordinator(audible, audnexus).searchBooks("query", US).map { it.asin } shouldContainExactly listOf("B1", "B2")
            }
        }

        test("composeChapters reaches Audnexus's accurate list when local has none (chain [local, audnexus, audible])") {
            // No LOCAL provider is registered, so the FirstAccurate walk must fall through to Audnexus —
            // now that Audnexus implements ChapterSource, its accurate list is selected.
            val audnexus =
                FakeProvider(
                    id = MetadataProviderId.AUDNEXUS,
                    chapters =
                        AppResult.Success(
                            ChapterListMeta(listOf(ChapterMeta(title = "Chapter 1", startMs = 0)), accurate = true),
                        ),
                )

            runTest {
                val chapters = coordinator(audnexus).composeChapters(identity(), US)
                chapters.shouldNotBeNull()
                chapters.accurate shouldBe true
                chapters.chapters.single().title shouldBe "Chapter 1"
            }
        }

        test("composeCharacters is empty by default — the honest empty slot, no built-in character source") {
            // The DEFAULT routes leave CHARACTERS empty and no FakeProvider supplies characters, so the
            // compose returns an empty list rather than fabricating data (the manual-entry story).
            val audible = FakeProvider(id = MetadataProviderId.AUDIBLE, core = AppResult.Success(BookCoreMeta(title = "T")))
            runTest {
                coordinator(audible).composeCharacters(identity(), US).shouldBeEmpty()
            }
        }

        test("searchContributors returns the first non-empty hit list walking the CONTRIBUTORS order") {
            // CONTRIBUTORS order is [audnexus, audible]; Audible has no ContributorSource in production, but
            // here both are fakes — Audnexus (first) supplies the hits.
            val audnexus =
                FakeProvider(
                    id = MetadataProviderId.AUDNEXUS,
                    contributorHits =
                        AppResult.Success(
                            listOf(
                                ContributorHitMeta(key = "A1", name = "Patrick Rothfuss"),
                                ContributorHitMeta(key = "A2", name = "Pat Rothfuss"),
                            ),
                        ),
                )

            runTest {
                coordinator(audnexus).searchContributors("rothfuss", US).map { it.key } shouldContainExactly listOf("A1", "A2")
            }
        }

        test("searchContributors returns empty when no provider has a hit") {
            val audnexus = FakeProvider(id = MetadataProviderId.AUDNEXUS)
            runTest {
                coordinator(audnexus).searchContributors("nobody", US).shouldBeEmpty()
            }
        }

        test("getContributor returns the first provider's profile walking the CONTRIBUTORS order") {
            val audnexus =
                FakeProvider(
                    id = MetadataProviderId.AUDNEXUS,
                    contributorProfile =
                        AppResult.Success(
                            ContributorMeta(key = "A1", name = "Patrick Rothfuss", description = "bio", imageUrl = "https://a/p.jpg"),
                        ),
                )

            runTest {
                val profile = coordinator(audnexus).getContributor("A1", US)
                profile.shouldNotBeNull()
                profile.name shouldBe "Patrick Rothfuss"
                profile.description shouldBe "bio"
                profile.imageUrl shouldBe "https://a/p.jpg"
            }
        }

        test("getContributor returns null when no provider has a profile") {
            val audnexus = FakeProvider(id = MetadataProviderId.AUDNEXUS)
            runTest {
                coordinator(audnexus).getContributor("A1", US).shouldBeNull()
            }
        }

        // ── M7: outage vs honest miss ──────────────────────────────────────────

        test("composeBook returns ExternalUnavailable when every core provider errored (an outage, not a miss)") {
            val audible = FakeProvider(MetadataProviderId.AUDIBLE, core = AppResult.Failure(MetadataError.ExternalUnavailable()))
            val audnexus = FakeProvider(MetadataProviderId.AUDNEXUS, core = AppResult.Failure(MetadataError.ExternalUnavailable()))

            runTest {
                val result = coordinator(audible, audnexus).composeBook(identity(), US)
                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.shouldBeInstanceOf<MetadataError.ExternalUnavailable>()
            }
        }

        test("composeBook is an honest miss (not a failure) when a working provider errors but another returns empty") {
            // Audible errors, but Audnexus successfully reports it has no entry — a genuine negative from
            // a working catalog, so the compose is a miss (null), not an outage.
            val audible = FakeProvider(MetadataProviderId.AUDIBLE, core = AppResult.Failure(MetadataError.ExternalUnavailable()))
            val audnexus = FakeProvider(MetadataProviderId.AUDNEXUS, core = AppResult.Success(null))

            runTest {
                coordinator(audible, audnexus).composeBook(identity(), US) shouldBe AppResult.Success(null)
            }
        }

        // ── M1: per-field winning provider provenance ──────────────────────────

        test("composeBook records the winning provider per field (M1)") {
            // Audible supplies title (wins BOOK_CORE order) but no description; Audnexus fills description.
            val audible =
                FakeProvider(
                    id = MetadataProviderId.AUDIBLE,
                    core = AppResult.Success(BookCoreMeta(title = "From Audible", description = null)),
                    genres = AppResult.Success(listOf(GenreMeta("Fantasy", GenreKind.GENRE))),
                )
            val audnexus =
                FakeProvider(
                    id = MetadataProviderId.AUDNEXUS,
                    core = AppResult.Success(BookCoreMeta(title = "From Audnexus", description = "Synopsis")),
                    series = AppResult.Success(listOf(SeriesMeta(key = "S1", title = "Series", sequence = "1"))),
                )

            runTest {
                val composed = coordinator(audible, audnexus).composeBookData(identity(), US)!!
                composed.fieldProviders[BookField.TITLE] shouldBe MetadataProviderId.AUDIBLE
                composed.fieldProviders[BookField.DESCRIPTION] shouldBe MetadataProviderId.AUDNEXUS
                composed.fieldProviders[BookField.GENRES] shouldBe MetadataProviderId.AUDIBLE
                composed.fieldProviders[BookField.SERIES] shouldBe MetadataProviderId.AUDNEXUS
            }
        }

        // ── M3: fan-out ∩ configured routes ────────────────────────────────────

        test("fan-out skips a provider the operator did not route to the domain (M3)") {
            // GENRES routed to Audible only; Audnexus has genres but must never be consulted for them.
            val audible =
                FakeProvider(
                    id = MetadataProviderId.AUDIBLE,
                    core = AppResult.Success(BookCoreMeta(title = "T")),
                    genres = AppResult.Success(emptyList()),
                )
            val audnexus =
                FakeProvider(
                    id = MetadataProviderId.AUDNEXUS,
                    genres = AppResult.Success(listOf(GenreMeta("Fantasy", GenreKind.GENRE))),
                )
            val routes =
                EnrichmentRoutes.DEFAULT.copy(
                    domainOrder =
                        EnrichmentRoutes.DEFAULT.domainOrder +
                            (MetadataDomain.GENRES to listOf(MetadataProviderId.AUDIBLE)),
                )

            runTest {
                val composed = coordinator(routes, audible, audnexus).composeBookData(identity(), US)!!
                composed.genres.shouldBeEmpty()
                audnexus.genresConsulted shouldBe false
                audible.genresConsulted shouldBe true
            }
        }

        // ── M2: refresh threads through to chapters ────────────────────────────

        test("composeChapters threads refresh through to the provider (M2)") {
            val audnexus =
                FakeProvider(
                    id = MetadataProviderId.AUDNEXUS,
                    chapters = AppResult.Success(ChapterListMeta(listOf(ChapterMeta(title = "Ch 1", startMs = 0)), accurate = true)),
                )

            runTest {
                coordinator(audnexus).composeChapters(identity(), US, refresh = true)
                audnexus.chaptersRefreshSeen shouldBe true
            }
        }

        test("composeChapters defaults refresh to false") {
            val audnexus =
                FakeProvider(
                    id = MetadataProviderId.AUDNEXUS,
                    chapters = AppResult.Success(ChapterListMeta(listOf(ChapterMeta(title = "Ch 1", startMs = 0)), accurate = true)),
                )

            runTest {
                coordinator(audnexus).composeChapters(identity(), US)
                audnexus.chaptersRefreshSeen shouldBe false
            }
        }

        // ── M6: genre ladders behind the SPI ───────────────────────────────────

        test("composeGenreLadders returns the first non-empty ladder walking the GENRES order (M6)") {
            val audible =
                FakeProvider(
                    id = MetadataProviderId.AUDIBLE,
                    genreLadders = AppResult.Success(listOf(listOf("Fiction", "Science Fiction & Fantasy", "Fantasy"))),
                )

            runTest {
                coordinator(audible).composeGenreLadders(identity(), US) shouldBe
                    listOf(listOf("Fiction", "Science Fiction & Fantasy", "Fantasy"))
            }
        }

        test("composeGenreLadders is empty when no provider exposes ladders") {
            val audible = FakeProvider(id = MetadataProviderId.AUDIBLE, core = AppResult.Success(BookCoreMeta(title = "T")))
            runTest {
                coordinator(audible).composeGenreLadders(identity(), US).shouldBeEmpty()
            }
        }
    })

/**
 * A single fake provider that implements every capability, each defaulting to an empty/miss result.
 * Configure only the capabilities a test cares about; the unconfigured ones contribute nothing to
 * the composition (an honest empty slot), which is exactly what the coordinator's first-non-empty
 * resolution expects.
 */
private class FakeProvider(
    override val id: MetadataProviderId,
    private val core: AppResult<BookCoreMeta?> = AppResult.Success(null),
    private val covers: AppResult<List<CoverMeta>> = AppResult.Success(emptyList()),
    private val genres: AppResult<List<GenreMeta>?> = AppResult.Success(null),
    private val series: AppResult<List<SeriesMeta>?> = AppResult.Success(null),
    private val chapters: AppResult<ChapterListMeta?> = AppResult.Success(null),
    private val genreLadders: AppResult<List<List<String>>?> = AppResult.Success(null),
    private val matches: AppResult<List<BookMatch>> = AppResult.Success(emptyList()),
    private val contributorHits: AppResult<List<ContributorHitMeta>> = AppResult.Success(emptyList()),
    private val contributorProfile: AppResult<ContributorMeta?> = AppResult.Success(null),
) : BookCoreSource,
    CoverSource,
    GenreSource,
    GenreLadderSource,
    SeriesSource,
    ChapterSource,
    ContributorSource,
    BookIdentitySource {
    /** The last [refresh] flag [getChapters] observed — lets a test assert refresh threads through. */
    var chaptersRefreshSeen: Boolean? = null
        private set

    /** Whether [getGenres] was consulted — lets a test assert an unrouted provider is skipped. */
    var genresConsulted: Boolean = false
        private set

    override suspend fun getBookCore(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<BookCoreMeta?> = core

    override suspend fun searchCovers(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<CoverMeta>> = covers

    override suspend fun getGenres(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<GenreMeta>?> {
        genresConsulted = true
        return genres
    }

    override suspend fun getGenreLadders(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<List<String>>?> = genreLadders

    override suspend fun getSeries(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<SeriesMeta>?> = series

    override suspend fun getChapters(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ChapterListMeta?> {
        chaptersRefreshSeen = refresh
        return chapters
    }

    override suspend fun searchBooks(
        query: String,
        locale: MetadataLocale,
    ): AppResult<List<BookMatch>> = matches

    override suspend fun searchContributors(
        name: String,
        locale: MetadataLocale,
    ): AppResult<List<ContributorHitMeta>> = contributorHits

    override suspend fun getContributor(
        key: String,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ContributorMeta?> = contributorProfile
}
