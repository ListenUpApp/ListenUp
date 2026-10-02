package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.ContributorHitMeta
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import com.calypsan.listenup.server.metadata.spi.GenreKind
import com.calypsan.listenup.server.metadata.spi.GenreMeta
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private val LOCALE = MetadataLocale.DEFAULT
private val WEIR = FakeHardcoverCatalog.Author(7L, "Andy Weir", bio = "Writes.", imageUrl = "https://hc.test/weir.jpg")

private val PHM =
    FakeHardcoverCatalog.Book(
        id = 427_578L,
        title = "Project Hail Mary",
        authors = listOf(WEIR),
        asin = "B08G9RZBTT",
        isbn13 = "9780593135204",
        description = "A lone astronaut must save the earth.",
        genres = listOf("Science Fiction" to 900, "Space Opera" to 300, "Fiction" to 800, "Humor" to 40, "Adventure" to 60, "Thriller" to 10),
        moods = listOf("adventurous" to 400, "funny" to 250, "hopeful" to 60, "dark" to 3, "tense" to 41, "inspiring" to 5, "emotional" to 39, "reflective" to 7),
        series = listOf(FakeHardcoverCatalog.Series(5L, "Project Hail Mary", 1.0)),
    )

/** A source over [HardcoverCatalogRig], a fake catalogue, a real link store and a table of book identities. */
private class SourceRig(
    val rig: HardcoverCatalogRig,
) {
    val hardcover = FakeHardcoverCatalog().apply { add(PHM) }
    val identities = mutableMapOf<String, BookIdentity>()
    val links = HardcoverBookLinkStore(rig.sql)
    val sourceSettings =
        HardcoverSourceSettings(
            apiTokens = rig.apiTokens,
            catalogToken = rig.catalog,
            graphQl = hardcover.client(),
            rateLimiter = NoWaitRateLimiter(),
            settings = ServerSettingsRepository(rig.sql, RegistrationPolicy.OPEN),
        )
    val source =
        HardcoverMetadataSource(
            graphQl = hardcover.client(),
            catalogToken = rig.catalog,
            matcher = HardcoverBookMatcher(hardcover.client(), NoWaitRateLimiter()),
            rateLimiter = NoWaitRateLimiter(),
            links = links,
            identities = HardcoverBookIdentities { identities[it] },
            sourceSettings = sourceSettings,
        )
}

private fun sourceTest(block: suspend SourceRig.() -> Unit) =
    withSqlDatabase {
        runTest {
            val rig = HardcoverCatalogRig(sql).apply { saveAdminToken() }
            SourceRig(rig).block()
        }
    }

private fun byAsin(asin: String = "B08G9RZBTT") = BookIdentity(asin = asin, title = "")

/** Hardcover as a metadata source (#1542): it finds the book carefully, fills gaps, and never guesses. */
class HardcoverMetadataSourceTest :
    FunSpec({
        test("a book someone linked by hand is read from that link, before any lookup and over an automatic link") {
            sourceTest {
                rig.sql.seedTestLibraryAndFolder()
                rig.sql.seedTestBook("book-1")
                rig.sql.seedTestUser("u1")
                rig.sql.seedTestUser("u2")
                hardcover.add(PHM.copy(id = 111L, asin = null, isbn13 = null, moods = listOf("dark" to 90)))
                links.recordAutomaticMatch("u1", "book-1", HardcoverMatch(111L, null, HardcoverMatchMethod.ASIN))
                links.linkManually("u2", "book-1", PHM.id, null)

                source.getMoods(BookIdentity(asin = "B08G9RZBTT", title = "", bookId = "book-1"), LOCALE) shouldBe
                    AppResult.Success(listOf("Adventurous", "Funny", "Hopeful", "Tense"))
                hardcover.operations shouldBe listOf("book_details")
            }
        }

        test("an automatic link is used when nobody linked the book by hand") {
            sourceTest {
                rig.sql.seedTestLibraryAndFolder()
                rig.sql.seedTestBook("book-1")
                rig.sql.seedTestUser("u1")
                links.recordAutomaticMatch("u1", "book-1", HardcoverMatch(PHM.id, null, HardcoverMatchMethod.ISBN))

                source.getBookCore(BookIdentity(title = "", bookId = "book-1"), LOCALE) shouldBe
                    AppResult.Success(BookCoreMeta(description = "A lone astronaut must save the earth."))
                hardcover.operations shouldBe listOf("book_details")
            }
        }

        test("with no link, the picked ASIN finds it") {
            sourceTest {
                source.getBookCore(byAsin(), LOCALE) shouldBe
                    AppResult.Success(BookCoreMeta(description = "A lone astronaut must save the earth."))
                hardcover.operations shouldBe listOf("edition_by_asin", "book_details")
            }
        }

        test("an ASIN Hardcover doesn't have falls through to the book's own ISBN") {
            sourceTest {
                identities["book-1"] = BookIdentity(isbn = "9780593135204", title = "Project Hail Mary", primaryAuthor = "Andy Weir")

                source.getSeries(BookIdentity(asin = "B000NOPE00", title = "", bookId = "book-1"), LOCALE) shouldBe
                    AppResult.Success(listOf(SeriesMeta(key = "hardcover:series:5", title = "Project Hail Mary", sequence = "1")))
                hardcover.operations shouldBe listOf("edition_by_asin", "edition_by_isbn", "book_details")
            }
        }

        test("then one confident title match, by the book's own title and author") {
            sourceTest {
                identities["book-1"] = BookIdentity(title = "Project Hail Mary", primaryAuthor = "Andy Weir")

                (source.getMoods(BookIdentity(asin = "B000NOPE00", title = "", bookId = "book-1"), LOCALE) as AppResult.Success).data!!.size shouldBe 4
                hardcover.operations shouldBe listOf("edition_by_asin", "books_by_title", "book_details")
            }
        }

        test("two confident title matches is no match: Hardcover's duplicates are never guessed between") {
            sourceTest {
                hardcover.add(PHM.copy(id = 999L, asin = null, isbn13 = null))
                identities["book-1"] = BookIdentity(title = "Project Hail Mary", primaryAuthor = "Andy Weir")

                source.getMoods(BookIdentity(asin = "B000NOPE00", title = "", bookId = "book-1"), LOCALE) shouldBe AppResult.Success(null)
                hardcover.operations shouldBe listOf("edition_by_asin", "books_by_title")
            }
        }

        test("no confident book means no Hardcover fields at all") {
            sourceTest {
                val stranger = byAsin("B000NOPE00")

                source.getBookCore(stranger, LOCALE) shouldBe AppResult.Success(null)
                source.getGenres(stranger, LOCALE) shouldBe AppResult.Success(null)
                source.getSeries(stranger, LOCALE) shouldBe AppResult.Success(null)
                source.getMoods(stranger, LOCALE) shouldBe AppResult.Success(null)
                hardcover.operations shouldBe listOf("edition_by_asin")
            }
        }

        test("one match preview costs one lookup and one details call, however many fields ask") {
            sourceTest {
                source.getBookCore(byAsin(), LOCALE)
                source.getGenres(byAsin(), LOCALE)
                source.getSeries(byAsin(), LOCALE)
                source.getMoods(byAsin(), LOCALE)

                hardcover.operations shouldBe listOf("edition_by_asin", "book_details")
            }
        }

        test("a refresh asks Hardcover again") {
            sourceTest {
                source.getBookCore(byAsin(), LOCALE)
                source.getBookCore(byAsin(), LOCALE, refresh = true)

                hardcover.operations shouldBe listOf("edition_by_asin", "book_details", "edition_by_asin", "book_details")
            }
        }

        test("genres: the five most voted, as genres") {
            sourceTest {
                source.getGenres(byAsin(), LOCALE) shouldBe
                    AppResult.Success(
                        listOf("Science Fiction", "Fiction", "Space Opera", "Adventure", "Humor").map { GenreMeta(it, GenreKind.GENRE) },
                    )
            }
        }

        test("with the Hardcover metadata switch off, nothing is asked and nothing is contributed") {
            sourceTest {
                sourceSettings.setMetadataEnabled(false)

                source.getMoods(byAsin(), LOCALE) shouldBe AppResult.Success(null)
                source.getBookCore(byAsin(), LOCALE) shouldBe AppResult.Success(null)
                source.searchContributors("Andy Weir", LOCALE) shouldBe AppResult.Success(emptyList())
                hardcover.asked shouldBe emptyList()
            }
        }

        test("with no token at all, nothing is asked and nothing is contributed") {
            sourceTest {
                rig.apiTokens.clear()

                source.getMoods(byAsin(), LOCALE) shouldBe AppResult.Success(null)
                hardcover.asked shouldBe emptyList()
            }
        }

        test("every read uses the admin's API token") {
            sourceTest {
                rig.connect("admin", com.calypsan.listenup.server.db.UserRoleColumn.ADMIN)

                source.getMoods(byAsin(), LOCALE)

                hardcover.asked.map { it.token }.toSet() shouldBe setOf(ADMIN_TOKEN)
            }
        }

        test("an unreachable Hardcover is a typed failure, never a throw, and is not remembered") {
            sourceTest {
                hardcover.unavailable = true

                source.getMoods(byAsin(), LOCALE).shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<HardcoverError.Unavailable>()

                hardcover.unavailable = false
                (source.getMoods(byAsin(), LOCALE) as AppResult.Success).data!!.size shouldBe 4
            }
        }

        test("contributors: an exact name finds Hardcover's author, and a Hardcover key opens their profile") {
            sourceTest {
                source.searchContributors("Andy Weir", LOCALE) shouldBe
                    AppResult.Success(listOf(ContributorHitMeta(key = "hardcover:author:7", name = "Andy Weir")))
                source.getContributor("hardcover:author:7", LOCALE) shouldBe
                    AppResult.Success(ContributorMeta("hardcover:author:7", "Andy Weir", "Writes.", "https://hc.test/weir.jpg"))
            }
        }

        test("an Audible or Audnexus key is not Hardcover's, so it is not asked") {
            sourceTest {
                source.getContributor("B00G0WYW92", LOCALE) shouldBe AppResult.Success(null)
                hardcover.asked shouldBe emptyList()
            }
        }
    })

/** The support floor on its own (deviation 16): at least 3 votes AND a tenth of the top mood's; at most six. */
class WellSupportedMoodsTest :
    FunSpec({
        test("three votes and a tenth of the top mood's, both; most voted first; capitalised") {
            wellSupportedMoods(
                listOf(
                    HardcoverTag("dark", 3),
                    HardcoverTag("adventurous", 400),
                    HardcoverTag("funny", 250),
                    HardcoverTag("hopeful", 60),
                    HardcoverTag("tense", 41),
                    HardcoverTag("inspiring", 5),
                    HardcoverTag("emotional", 39),
                    HardcoverTag("reflective", 7),
                ),
            ) shouldBe listOf("Adventurous", "Funny", "Hopeful", "Tense")
        }

        test("a book whose only mood has one or two votes offers none") {
            wellSupportedMoods(listOf(HardcoverTag("cozy", 1))) shouldBe emptyList()
            wellSupportedMoods(listOf(HardcoverTag("cozy", 2))) shouldBe emptyList()
        }

        test("on a lightly tagged book, three votes is enough when it is also a tenth of the top") {
            wellSupportedMoods(listOf(HardcoverTag("cozy", 4), HardcoverTag("funny", 3), HardcoverTag("sad", 2))) shouldBe
                listOf("Cozy", "Funny")
        }

        test("never more than six, the most voted kept") {
            wellSupportedMoods((1..8).map { HardcoverTag("mood$it", 100 - it) }) shouldBe
                (1..6).map { "Mood$it" }
        }

        test("no moods is no moods") {
            wellSupportedMoods(emptyList()) shouldBe emptyList()
        }
    })
