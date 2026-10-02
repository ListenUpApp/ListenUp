package com.calypsan.listenup.server.metadata

import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreSource
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.ContributorHitMeta
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import com.calypsan.listenup.server.metadata.spi.ContributorSource
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.GenreKind
import com.calypsan.listenup.server.metadata.spi.GenreMeta
import com.calypsan.listenup.server.metadata.spi.GenreSource
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.metadata.spi.MoodSource
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import com.calypsan.listenup.server.metadata.spi.SeriesSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList

private val US = MetadataLocale("us")
private val BOOK = BookIdentity(asin = "B08G9RZBTT", title = "")
private val AUDIBLE = MetadataProviderId.AUDIBLE
private val AUDNEXUS = MetadataProviderId.AUDNEXUS
private val HARDCOVER = MetadataProviderId.HARDCOVER

/** A catalog with whichever capabilities a test fills; [fails] makes every answer a provider failure. */
private class Catalog(
    override val id: MetadataProviderId,
    private val core: BookCoreMeta? = null,
    private val genres: List<String> = emptyList(),
    private val series: List<SeriesMeta> = emptyList(),
    private val moods: List<String> = emptyList(),
    private val hits: List<ContributorHitMeta> = emptyList(),
    private val profiles: Map<String, ContributorMeta> = emptyMap(),
    private val fails: Boolean = false,
) : BookCoreSource,
    GenreSource,
    SeriesSource,
    MoodSource,
    ContributorSource {
    val asked = CopyOnWriteArrayList<String>()

    private fun <T> answer(
        label: String,
        value: T,
    ): AppResult<T> {
        asked += label
        return if (fails) AppResult.Failure(HardcoverError.Unavailable()) else AppResult.Success(value)
    }

    override suspend fun getBookCore(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<BookCoreMeta?> = answer("core", core)

    override suspend fun getGenres(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<GenreMeta>?> = answer("genres", genres.map { GenreMeta(it, GenreKind.GENRE) }.ifEmpty { null })

    override suspend fun getSeries(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<SeriesMeta>?> = answer("series", series.ifEmpty { null })

    override suspend fun getMoods(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<String>?> = answer("moods", moods.ifEmpty { null })

    override suspend fun searchContributors(
        name: String,
        locale: MetadataLocale,
    ): AppResult<List<ContributorHitMeta>> = answer("search:$name", hits.filter { it.name == name })

    override suspend fun getContributor(
        key: String,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ContributorMeta?> = answer("profile:$key", profiles[key])
}

private fun coordinator(vararg catalogs: Catalog) =
    EnrichmentCoordinator(MetadataProviderRegistry(catalogs.toList()), EnrichmentRoutes.DEFAULT)

private suspend fun EnrichmentCoordinator.compose(): ComposedBook? = (composeBook(BOOK, US) as AppResult.Success).data

/** The coordinator's rules for a gap-filling source (#1542), with Hardcover last in every default route. */
class EnrichmentCoordinatorGapFillTest :
    FunSpec({
        test("Hardcover's description fills the gap only when Audible and Audnexus have none") {
            runTest {
                val hardcover = Catalog(HARDCOVER, core = BookCoreMeta(description = "From Hardcover."))

                val gap = coordinator(Catalog(AUDIBLE, core = BookCoreMeta(title = "PHM")), hardcover).compose()!!
                gap.core.description shouldBe "From Hardcover."
                gap.fieldProviders[BookField.DESCRIPTION] shouldBe HARDCOVER

                val full =
                    coordinator(Catalog(AUDIBLE, core = BookCoreMeta(title = "PHM", description = "From Audible.")), hardcover).compose()!!
                full.core.description shouldBe "From Audible."
                full.fieldProviders[BookField.DESCRIPTION] shouldBe AUDIBLE
            }
        }

        test("an Audible miss stays a miss, whatever Hardcover knows about the book") {
            runTest {
                val composed =
                    coordinator(
                        Catalog(AUDIBLE),
                        Catalog(HARDCOVER, core = BookCoreMeta(description = "From Hardcover."), moods = listOf("Hopeful")),
                    ).composeBook(BOOK, US)

                composed shouldBe AppResult.Success(null)
            }
        }

        test("every identifying catalog failing is an outage, even when Hardcover answered") {
            runTest {
                val composed =
                    coordinator(
                        Catalog(AUDIBLE, fails = true),
                        Catalog(AUDNEXUS, fails = true),
                        Catalog(HARDCOVER, core = BookCoreMeta(description = "From Hardcover.")),
                    ).composeBook(BOOK, US)

                composed.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<MetadataError.ExternalUnavailable>()
            }
        }

        test("genres union: Audible's first, then Hardcover's new ones, each recorded with Hardcover") {
            runTest {
                val composed =
                    coordinator(
                        Catalog(AUDIBLE, core = BookCoreMeta(title = "PHM"), genres = listOf("Science Fiction")),
                        Catalog(HARDCOVER, genres = listOf("science fiction", "Space Opera")),
                    ).compose()!!

                composed.genres.map { it.name } shouldBe listOf("Science Fiction", "Space Opera")
                composed.genreProviders shouldBe mapOf("Space Opera" to HARDCOVER)
                composed.fieldProviders[BookField.GENRES] shouldBe AUDIBLE
            }
        }

        test("with no genres from Audible or Audnexus, Hardcover's stand alone and every one is Hardcover's") {
            runTest {
                val composed =
                    coordinator(
                        Catalog(AUDIBLE, core = BookCoreMeta(title = "PHM")),
                        Catalog(HARDCOVER, genres = listOf("Science Fiction", "Space Opera")),
                    ).compose()!!

                composed.genres.map { it.name } shouldBe listOf("Science Fiction", "Space Opera")
                composed.genreProviders shouldBe mapOf("Science Fiction" to HARDCOVER, "Space Opera" to HARDCOVER)
                composed.fieldProviders[BookField.GENRES] shouldBe HARDCOVER
            }
        }

        test("moods come from Hardcover alone, even when another catalog offers some") {
            runTest {
                val composed =
                    coordinator(
                        Catalog(AUDIBLE, core = BookCoreMeta(title = "PHM")),
                        Catalog(AUDNEXUS, moods = listOf("Tense")),
                        Catalog(HARDCOVER, moods = listOf("Hopeful", "Funny")),
                    ).compose()!!

                composed.moods shouldBe listOf("Hopeful", "Funny")
                composed.fieldProviders[BookField.MOODS] shouldBe HARDCOVER
            }
        }

        test("a series falls back to Hardcover only when Audible and Audnexus have none") {
            runTest {
                val hardcover = Catalog(HARDCOVER, series = listOf(SeriesMeta("hardcover:series:5", "Project Hail Mary", "1")))

                coordinator(Catalog(AUDIBLE, core = BookCoreMeta(title = "PHM")), hardcover).compose()!!.series.single().key shouldBe
                    "hardcover:series:5"
                coordinator(
                    Catalog(AUDIBLE, core = BookCoreMeta(title = "PHM"), series = listOf(SeriesMeta("S1", "Audible Series", "2"))),
                    hardcover,
                ).compose()!!.series.single().key shouldBe "S1"
            }
        }

        test("a Hardcover failure leaves everything else intact") {
            runTest {
                val composed =
                    coordinator(
                        Catalog(AUDIBLE, core = BookCoreMeta(title = "PHM", description = "From Audible."), genres = listOf("Science Fiction")),
                        Catalog(HARDCOVER, fails = true),
                    ).compose()!!

                composed.core.title shouldBe "PHM"
                composed.core.description shouldBe "From Audible."
                composed.genres.map { it.name } shouldBe listOf("Science Fiction")
                composed.moods shouldBe emptyList()
                composed.fieldProviders.values shouldNotContain HARDCOVER
            }
        }

        test("a contributor profile missing its photo takes Hardcover's, found by the person's exact name") {
            runTest {
                val audnexus =
                    Catalog(AUDNEXUS, profiles = mapOf("B00G0WYW92" to ContributorMeta("B00G0WYW92", "Andy Weir", description = "Audnexus bio.")))
                val hardcover =
                    Catalog(
                        HARDCOVER,
                        hits = listOf(ContributorHitMeta("hardcover:author:7", "Andy Weir")),
                        profiles =
                            mapOf(
                                "hardcover:author:7" to
                                    ContributorMeta("hardcover:author:7", "Andy Weir", "Hardcover bio.", "https://hc.test/weir.jpg"),
                            ),
                    )

                val profile = coordinator(audnexus, hardcover).getContributor("B00G0WYW92", US)!!

                profile.key shouldBe "B00G0WYW92"
                profile.description shouldBe "Audnexus bio."
                profile.imageUrl shouldBe "https://hc.test/weir.jpg"
            }
        }

        test("a complete profile never asks Hardcover to fill anything") {
            runTest {
                val audnexus =
                    Catalog(
                        AUDNEXUS,
                        profiles = mapOf("B00G0WYW92" to ContributorMeta("B00G0WYW92", "Andy Weir", "Bio.", "https://audnexus.test/weir.jpg")),
                    )
                val hardcover = Catalog(HARDCOVER, hits = listOf(ContributorHitMeta("hardcover:author:7", "Andy Weir")))

                coordinator(audnexus, hardcover).getContributor("B00G0WYW92", US)!!.imageUrl shouldBe "https://audnexus.test/weir.jpg"
                hardcover.asked shouldBe listOf("profile:B00G0WYW92")
            }
        }

        test("no profile anywhere is still no profile") {
            runTest {
                coordinator(Catalog(AUDNEXUS), Catalog(HARDCOVER)).getContributor("B00G0WYW92", US).shouldBeNull()
            }
        }
    })
