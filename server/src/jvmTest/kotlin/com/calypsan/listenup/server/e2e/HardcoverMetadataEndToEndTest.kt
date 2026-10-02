package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.hardcover.ADMIN_TOKEN
import com.calypsan.listenup.server.hardcover.FakeHardcoverCatalog
import com.calypsan.listenup.server.hardcover.HardcoverBookIdentities
import com.calypsan.listenup.server.hardcover.HardcoverBookLinkStore
import com.calypsan.listenup.server.hardcover.HardcoverBookMatcher
import com.calypsan.listenup.server.hardcover.HardcoverCatalogRig
import com.calypsan.listenup.server.hardcover.HardcoverMetadataSource
import com.calypsan.listenup.server.hardcover.HardcoverSourceSettings
import com.calypsan.listenup.server.hardcover.LookupRig
import com.calypsan.listenup.server.hardcover.NoWaitRateLimiter
import com.calypsan.listenup.server.hardcover.OneBookAudible
import com.calypsan.listenup.server.hardcover.PHM_ASIN
import com.calypsan.listenup.server.hardcover.audibleHailMary
import com.calypsan.listenup.server.hardcover.lookupRig
import com.calypsan.listenup.server.hardcover.scannedBook
import com.calypsan.listenup.server.ratings.toIdentity
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * #1542 end to end, service-level (a full boot can't fake Audible): the real lookup service, coordinator,
 * Hardcover metadata source, catalogue token and sealed token store, over SQLite, a fake Audible and a fake
 * Hardcover. Matching Project Hail Mary yields Audible's core plus Hardcover's moods, extra genre, series and
 * description, every Hardcover read on the admin's API token; applying writes exactly the chosen moods.
 */
class HardcoverMetadataEndToEndTest :
    FunSpec({
        test("a match gets Audible's core and Hardcover's gaps, and apply writes the moods chosen") {
            withSqlDatabase {
                runTest {
                    sql.seedTestLibraryAndFolder()
                    val hardcover =
                        FakeHardcoverCatalog().apply {
                            add(
                                FakeHardcoverCatalog.Book(
                                    id = 427_578L,
                                    title = "Project Hail Mary",
                                    authors = listOf(FakeHardcoverCatalog.Author(7L, "Andy Weir")),
                                    asin = PHM_ASIN,
                                    description = "A lone astronaut must save the earth.",
                                    genres = listOf("Science Fiction" to 900, "Space Opera" to 300),
                                    moods = listOf("adventurous" to 400, "funny" to 250, "hopeful" to 60, "dark" to 3),
                                    series = listOf(FakeHardcoverCatalog.Series(5L, "Project Hail Mary", 1.0)),
                                ),
                            )
                        }
                    val catalogRig = HardcoverCatalogRig(sql).apply { saveAdminToken() }
                    // The source reads the book's own identity through the rig's repository, which exists only
                    // once the rig does; the lambda runs at lookup time, after `rig` is assigned.
                    lateinit var rig: LookupRig
                    val source =
                        HardcoverMetadataSource(
                            graphQl = hardcover.client(),
                            catalogToken = catalogRig.catalog,
                            matcher = HardcoverBookMatcher(hardcover.client(), NoWaitRateLimiter()),
                            rateLimiter = NoWaitRateLimiter(),
                            links = HardcoverBookLinkStore(sql),
                            identities = HardcoverBookIdentities { rig.books.findById(BookId(it))?.toIdentity() },
                            sourceSettings =
                                HardcoverSourceSettings(
                                    catalogRig.apiTokens,
                                    catalogRig.catalog,
                                    hardcover.client(),
                                    NoWaitRateLimiter(),
                                    ServerSettingsRepository(sql, RegistrationPolicy.OPEN),
                                ),
                        )
                    rig = lookupRig(this@withSqlDatabase, OneBookAudible(audibleHailMary()), listOf(source))
                    rig.books.upsert(scannedBook("book-1"), clientOpId = null)

                    val preview =
                        rig.service
                            .getBookMetadata(PHM_ASIN, MetadataLocale("us"), BookId("book-1"))
                            .shouldBeInstanceOf<AppResult.Success<MetadataBook?>>()
                            .data!!

                    preview.title shouldBe "Project Hail Mary"
                    preview.authors.single().name shouldBe "Andy Weir"
                    preview.description shouldBe "A lone astronaut must save the earth."
                    preview.moods shouldBe listOf("Adventurous", "Funny", "Hopeful")
                    preview.genres shouldBe listOf("Science Fiction", "Space Opera")
                    preview.series.single().asin shouldBe "hardcover:series:5"
                    preview.series.single().sequence shouldBe "1"
                    val provenance = preview.matchProvenance!!
                    provenance.fallbackFields[BookField.MOODS] shouldBe "Hardcover"
                    provenance.fallbackFields[BookField.SERIES] shouldBe "Hardcover"
                    provenance.fallbackFields[BookField.DESCRIPTION] shouldBe "Hardcover"
                    provenance.fallbackFields.containsKey(BookField.TITLE) shouldBe false
                    provenance.genreSources shouldBe mapOf("Space Opera" to "Hardcover")
                    hardcover.asked.map { it.token }.toSet() shouldBe setOf(ADMIN_TOKEN)

                    val chosen =
                        MetadataApplySelection(
                            title = true,
                            subtitle = false,
                            description = true,
                            publisher = false,
                            releaseDate = false,
                            language = false,
                            cover = false,
                            authorAsins = emptySet(),
                            narratorAsins = emptySet(),
                            seriesAsins = setOf("hardcover:series:5"),
                            moods = setOf("Adventurous", "Hopeful"),
                        )
                    rig.service.applyBookMetadata(BookId("book-1"), PHM_ASIN, MetadataLocale("us"), chosen)
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    rig.moodNames("book-1") shouldContainExactlyInAnyOrder listOf("Adventurous", "Hopeful")
                    val applied = rig.books.findById(BookId("book-1"))!!
                    applied.description shouldBe "A lone astronaut must save the earth."
                    applied.series.single().name shouldBe "Project Hail Mary"
                    hardcover.operations.count { it == "book_details" } shouldBe 1
                }
            }
        }
    })
