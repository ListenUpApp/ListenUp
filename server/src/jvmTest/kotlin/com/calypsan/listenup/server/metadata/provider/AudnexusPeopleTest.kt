@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.audnexus.AudnexusApi
import com.calypsan.listenup.server.metadata.audnexus.AudnexusAuthor
import com.calypsan.listenup.server.metadata.audnexus.AudnexusAuthorProfile
import com.calypsan.listenup.server.metadata.audnexus.AudnexusBook
import com.calypsan.listenup.server.metadata.audnexus.AudnexusChapters
import com.calypsan.listenup.server.metadata.spi.PersonAnswer
import com.calypsan.listenup.server.metadata.spi.PersonLibraryBook
import com.calypsan.listenup.server.metadata.spi.PersonLookup
import com.calypsan.listenup.server.metadata.spi.PersonStep
import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

private val CLOCK = FixedClock(Instant.parse("2026-10-06T12:00:00Z"))

/** An Audnexus with a few authors and books, in memory. */
private class PeopleAudnexus(
    val books: Map<String, AudnexusBook> = emptyMap(),
    val profiles: Map<String, AudnexusAuthorProfile> = emptyMap(),
    val searchFails: Boolean = false,
) : AudnexusApi {
    override suspend fun getBook(
        asin: String,
        region: String,
    ): AppResult<AudnexusBook?> = AppResult.Success(books[asin])

    override suspend fun getChapters(
        asin: String,
        region: String,
    ): AppResult<AudnexusChapters?> = AppResult.Success(null)

    override suspend fun searchAuthors(
        name: String,
        region: String,
    ): AppResult<List<AudnexusAuthor>> =
        if (searchFails) {
            AppResult.Failure(MetadataError.ExternalUnavailable())
        } else {
            AppResult.Success(
                profiles.values
                    .filter { it.name.contains(name, ignoreCase = true) }
                    .map { AudnexusAuthor(it.asin, it.name) },
            )
        }

    override suspend fun getAuthor(
        asin: String,
        region: String,
    ): AppResult<AudnexusAuthorProfile?> = AppResult.Success(profiles[asin])
}

private val WEIR = AudnexusAuthorProfile(asin = "B00G0WYW92", name = "Andy Weir", description = "Bio.", image = "https://a/weir.jpg")
private val TAYLOR = AudnexusAuthorProfile(asin = "B00TAYLOR1", name = "Dennis E. Taylor")
private val PHM = AudnexusBook(asin = "B08G9RZBTT", title = "Project Hail Mary", authors = listOf(AudnexusAuthor(WEIR.asin, WEIR.name)))

private fun author(
    name: String = "Andy Weir",
    keys: List<String> = emptyList(),
    books: List<PersonLibraryBook> = emptyList(),
) = PersonLookup(name, keys, books)

private fun audnexusPeopleTest(
    api: PeopleAudnexus,
    block: suspend (AudnexusProvider) -> Unit,
) = withSqlDatabase {
    runTest { block(AudnexusProvider(client = api, cache = MetadataCacheRepository(sql, clock = CLOCK), clock = CLOCK)) }
}

private suspend fun AudnexusProvider.answer(lookup: PersonLookup): PersonAnswer =
    findPeople(lookup, MetadataLocale("us")).shouldBeInstanceOf<AppResult.Success<PersonAnswer>>().data

/** Audible authors in a people Find: by name, by your books' Audible credits, and by the person's own ASIN. */
class AudnexusPeopleTest :
    FunSpec({
        test("a name search finds the author with the photo from their profile") {
            audnexusPeopleTest(PeopleAudnexus(profiles = mapOf(WEIR.asin to WEIR))) { provider ->
                val answer = provider.answer(author())

                answer.steps shouldBe setOf(PersonStep.NAME)
                val weir = answer.people.single()
                weir.key shouldBe WEIR.asin
                weir.roles shouldBe setOf(ContributorRole.AUTHOR)
                weir.photoUrl shouldBe "https://a/weir.jpg"
                weir.foundByName shouldBe true
            }
        }

        test("your books' Audible credits name the author and count the books, whatever your credit says") {
            val api = PeopleAudnexus(books = mapOf(PHM.asin to PHM), profiles = mapOf(WEIR.asin to WEIR, TAYLOR.asin to TAYLOR))
            audnexusPeopleTest(api) { provider ->
                val book =
                    PersonLibraryBook(
                        "b-phm",
                        "Project Hail Mary",
                        asin = PHM.asin,
                        isbn = null,
                        refs = emptyList(),
                        roles = setOf(ContributorRole.TRANSLATOR),
                    )
                val answer = provider.answer(author(name = "", books = listOf(book)))

                answer.steps shouldBe setOf(PersonStep.VIA_BOOKS)
                answer.people.single().key shouldBe WEIR.asin
                answer.people.single().creditedBookIds shouldBe setOf("b-phm")
                answer.people.single().foundByName shouldBe false
            }
        }

        test("the person's own ASIN is the current link") {
            audnexusPeopleTest(PeopleAudnexus(profiles = mapOf(WEIR.asin to WEIR))) { provider ->
                val answer = provider.answer(author(name = "", keys = listOf(WEIR.asin)))
                answer.steps shouldBe setOf(PersonStep.LINK)
                answer.people.single().viaLink shouldBe true
            }
        }

        test("a failed search fails the answer") {
            audnexusPeopleTest(PeopleAudnexus(searchFails = true)) { provider ->
                provider.findPeople(author(), MetadataLocale("us")).shouldBeInstanceOf<AppResult.Failure>()
            }
        }
    })
