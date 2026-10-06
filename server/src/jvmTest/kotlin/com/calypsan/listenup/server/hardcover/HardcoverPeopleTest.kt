package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.UnavailableReason
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.spi.FindAvailability
import com.calypsan.listenup.server.metadata.spi.PersonAnswer
import com.calypsan.listenup.server.metadata.spi.PersonLibraryBook
import com.calypsan.listenup.server.metadata.spi.PersonLookup
import com.calypsan.listenup.server.metadata.spi.PersonStep
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private val WEIR = FakeHardcoverCatalog.Author(123_645L, "Andy Weir", bio = "Space nerd.", imageUrl = "https://hc/weir.jpg")
private val PORTER = FakeHardcoverCatalog.Author(250_716L, "Ray Porter", imageUrl = "https://hc/porter.jpg")
private val TAYLOR = FakeHardcoverCatalog.Author(233_077L, "Dennis E. Taylor")

private val PHM =
    FakeHardcoverCatalog.Book(
        id = 427_578L,
        title = "Project Hail Mary",
        authors = listOf(WEIR),
        narrators = listOf(PORTER),
        asin = "B08G9RZBTT",
        isbn13 = "9780593135204",
        audioSeconds = 58_253L,
    )
private val HEAVENS_RIVER =
    FakeHardcoverCatalog.Book(
        id = 428_002L,
        title = "Heaven's River",
        authors = listOf(TAYLOR),
        narrators = listOf(PORTER),
        asin = "B088C51F5H",
    )

private class PeopleRig(
    val rig: HardcoverCatalogRig,
) {
    val hardcover =
        FakeHardcoverCatalog().apply {
            add(PHM)
            add(HEAVENS_RIVER)
        }
    val source =
        HardcoverMetadataSource(
            graphQl = hardcover.client(),
            catalogToken = rig.catalog,
            matcher = HardcoverBookMatcher(hardcover.client(), NoWaitRateLimiter()),
            rateLimiter = NoWaitRateLimiter(),
            links = HardcoverBookLinkStore(rig.sql),
            identities = HardcoverBookIdentities { null },
            sourceSettings =
                HardcoverSourceSettings(
                    apiTokens = rig.apiTokens,
                    catalogToken = rig.catalog,
                    graphQl = hardcover.client(),
                    rateLimiter = NoWaitRateLimiter(),
                    settings = ServerSettingsRepository(rig.sql, RegistrationPolicy.OPEN),
                ),
        )

    suspend fun answer(lookup: PersonLookup): PersonAnswer =
        source.findPeople(lookup, MetadataLocale.DEFAULT).shouldBeInstanceOf<AppResult.Success<PersonAnswer>>().data
}

private fun peopleTest(
    withToken: Boolean = true,
    block: suspend PeopleRig.() -> Unit,
) = withSqlDatabase {
    runTest {
        val rig = HardcoverCatalogRig(sql).apply { if (withToken) saveAdminToken() }
        PeopleRig(rig).block()
    }
}

private fun book(
    id: String,
    asin: String? = null,
    isbn: String? = null,
    refs: List<ExternalRef> = emptyList(),
) = PersonLibraryBook(bookId = id, title = id, asin = asin, isbn = isbn, refs = refs)

private fun narrator(
    name: String = "Ray Porter",
    keys: List<String> = emptyList(),
    books: List<PersonLibraryBook> = emptyList(),
) = PersonLookup(name = name, role = ContributorRole.NARRATOR, keys = keys, books = books)

/**
 * Hardcover finds narrators (matching redesign PR 4): a narrator is a Hardcover author credited on editions with a
 * narrator role. Two calls at most — the author index, then one batched read of the people and of your books'
 * credits.
 */
class HardcoverPeopleTest :
    FunSpec({
        test("Hardcover has profiles for authors and narrators") {
            peopleTest { source.profileRoles shouldBe setOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR) }
        }

        test("a narrator search finds the narrator with roles, photo, works, and your books he narrates") {
            peopleTest {
                val answer = answer(narrator(books = listOf(book("b-phm", asin = "B08G9RZBTT"), book("b-hr", asin = "B088C51F5H"))))

                hardcover.operations shouldBe listOf("people_search", "people_details")
                answer.steps shouldBe setOf(PersonStep.NAME, PersonStep.VIA_BOOKS)
                val porter = answer.people.single()
                porter.key shouldBe "250716"
                porter.name shouldBe "Ray Porter"
                porter.roles shouldBe setOf(ContributorRole.NARRATOR)
                porter.photoUrl shouldBe "https://hc/porter.jpg"
                porter.knownWorks shouldBe listOf("Project Hail Mary", "Heaven's River")
                porter.worksCount shouldBe 2
                porter.creditedBookIds shouldBe setOf("b-phm", "b-hr")
                porter.foundByName shouldBe true
                porter.viaLink shouldBe false
            }
        }

        test("credits in another role never count: the author of your book isn't credited as its narrator") {
            peopleTest {
                val answer = answer(narrator(name = "Andy Weir", books = listOf(book("b-phm", asin = "B08G9RZBTT"))))

                val weir = answer.people.first { it.key == "123645" }
                weir.roles shouldBe setOf(ContributorRole.AUTHOR)
                weir.creditedBookIds shouldBe emptySet()
                // Ray Porter narrates that book, so he comes back as a co-credit candidate, not found by name.
                answer.people.first { it.key == "250716" }.foundByName shouldBe false
            }
        }

        test("an author search counts book credits, never the narrator's") {
            peopleTest {
                val answer =
                    source
                        .findPeople(
                            PersonLookup("Andy Weir", ContributorRole.AUTHOR, emptyList(), listOf(book("b-phm", asin = "B08G9RZBTT"))),
                            MetadataLocale.DEFAULT,
                        ).shouldBeInstanceOf<AppResult.Success<PersonAnswer>>()
                        .data

                answer.people.map { it.key to it.creditedBookIds } shouldBe listOf("123645" to setOf("b-phm"))
            }
        }

        test("an ISBN and a linked Hardcover book identify your book too") {
            peopleTest {
                val answer =
                    answer(
                        narrator(
                            name = "",
                            books =
                                listOf(
                                    book("b-phm", isbn = "9780593135204"),
                                    book("b-hr", refs = listOf(ExternalRef("hardcover", "428002"))),
                                ),
                        ),
                    )

                hardcover.operations shouldBe listOf("people_details")
                answer.steps shouldBe setOf(PersonStep.VIA_BOOKS)
                answer.people.single().creditedBookIds shouldBe setOf("b-phm", "b-hr")
            }
        }

        test("the person's own ref is read in the same batch and marked as the current link") {
            peopleTest {
                val answer = answer(narrator(name = "Ray", keys = listOf("250716")))

                hardcover.operations shouldBe listOf("people_search", "people_details")
                answer.steps shouldContainExactly setOf(PersonStep.LINK, PersonStep.NAME)
                answer.people.first().key shouldBe "250716"
                answer.people.first().viaLink shouldBe true
            }
        }

        test("nothing to ask means no call at all") {
            peopleTest {
                answer(narrator(name = " ")).people shouldBe emptyList()
                hardcover.operations shouldBe emptyList()
            }
        }

        test("off or unconfigured, Hardcover says so; throttled, it says when to try again") {
            peopleTest(withToken = false) {
                source.personAvailability() shouldBe FindAvailability.Unavailable(UnavailableReason.NOT_CONFIGURED)
            }
            peopleTest {
                hardcover.throttleAfterMs = 12_000L
                source
                    .findPeople(narrator(), MetadataLocale.DEFAULT)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error shouldBe MetadataError.ExternalRateLimited(retryAfterSeconds = 12)
            }
        }
    })
