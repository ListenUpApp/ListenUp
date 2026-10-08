package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.match.UnavailableReason
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.spi.FindAnswer
import com.calypsan.listenup.server.metadata.spi.FindAvailability
import com.calypsan.listenup.server.metadata.spi.FindKey
import com.calypsan.listenup.server.metadata.spi.FindLookup
import com.calypsan.listenup.server.metadata.spi.FindStep
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private val WEIR = FakeHardcoverCatalog.Author(7L, "Andy Weir")
private val PORTER = FakeHardcoverCatalog.Author(8L, "Ray Porter")

private val PHM =
    FakeHardcoverCatalog.Book(
        id = 427_578L,
        title = "Project Hail Mary",
        subtitle = "A Novel",
        authors = listOf(WEIR),
        narrators = listOf(PORTER),
        asin = "B08G9PRS1K",
        isbn13 = "9780593135204",
        audioSeconds = 58_253L,
        editionFormat = "Audible",
    )

private val GUIDE =
    FakeHardcoverCatalog.Book(
        id = 510_290L,
        title = "Project Hail Mary Study Guide",
        authors = listOf(FakeHardcoverCatalog.Author(9L, "Milkyway Media")),
    )

@Suppress("UseDataClass") // A rig of live, mutable fakes, not a value; identity equality is the right semantics.
private class FindRig(
    val rig: HardcoverCatalogRig,
) {
    val hardcover =
        FakeHardcoverCatalog().apply {
            add(PHM)
            add(GUIDE)
        }
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
            identities = HardcoverBookIdentities { null },
            sourceSettings = sourceSettings,
        )
}

private fun findTest(
    withToken: Boolean = true,
    block: suspend FindRig.() -> Unit,
) = withSqlDatabase {
    runTest {
        val rig = HardcoverCatalogRig(sql).apply { if (withToken) saveAdminToken() }
        FindRig(rig).block()
    }
}

private fun lookup(
    keys: List<FindKey> = emptyList(),
    asin: String? = null,
    isbn: String? = null,
    identify: Boolean = true,
) = FindLookup(
    bookId = "book-1",
    identify = identify,
    keys = keys,
    asin = asin,
    isbn = isbn,
    text = "Project Hail Mary Andy Weir",
    title = "Project Hail Mary",
    primaryAuthor = "Andy Weir",
)

private suspend fun FindRig.answer(lookup: FindLookup): FindAnswer =
    source.findBooks(lookup, MetadataLocale.DEFAULT).shouldBeInstanceOf<AppResult.Success<FindAnswer>>().data

/** Hardcover in Find (matching redesign PR 2): at most two calls, one hit per book, from its audiobook edition. */
class HardcoverFindTest :
    FunSpec({
        test("a search and one batched read: the audiobook edition supplies length, narrators and ASIN") {
            findTest {
                val answer = answer(lookup())

                hardcover.operations shouldBe listOf("search", "find_books")
                answer.steps shouldBe setOf(FindStep.TEXT)
                val phm = answer.books.first { it.key == "427578" }
                phm.title shouldBe "Project Hail Mary"
                phm.subtitle shouldBe "A Novel"
                phm.authors shouldBe listOf("Andy Weir")
                phm.narrators shouldBe listOf("Ray Porter")
                phm.durationMs shouldBe 58_253_000L
                phm.asin shouldBe "B08G9PRS1K"
                phm.region shouldBe null
                phm.viaLink shouldBe false
                answer.books.first { it.key == "510290" }.durationMs shouldBe null
            }
        }

        test("a hand-picked link is read in the same batch, and replaces the ASIN and ISBN lookups") {
            findTest {
                rig.sql.seedTestLibraryAndFolder()
                rig.sql.seedTestBook("book-1")
                rig.sql.seedTestUser("u1")
                links.linkManually("u1", "book-1", PHM.id, null)

                val answer = answer(lookup(asin = "B08G9PRS1K", isbn = "9780593135204"))

                hardcover.operations shouldBe listOf("search", "find_books")
                answer.steps shouldBe setOf(FindStep.LINK, FindStep.TEXT)
                answer.books.first().key shouldBe "427578"
                answer.books.first().viaLink shouldBe true
            }
        }

        test("the book's Hardcover ref is a link too") {
            findTest {
                val answer = answer(lookup(keys = listOf(FindKey("427578"))))
                answer.steps shouldBe setOf(FindStep.LINK, FindStep.TEXT)
                answer.books.first().viaLink shouldBe true
            }
        }

        test("with no link, the ASIN and ISBN fold into the same batched read") {
            findTest {
                val answer = answer(lookup(asin = "B08G9PRS1K", isbn = "9780593135204"))

                hardcover.operations shouldBe listOf("search", "find_books")
                answer.steps shouldBe setOf(FindStep.ASIN, FindStep.ISBN, FindStep.TEXT)
                answer.books.count { it.key == "427578" } shouldBe 1
            }
        }

        test("'Search by title' never reads a link") {
            findTest {
                rig.sql.seedTestLibraryAndFolder()
                rig.sql.seedTestBook("book-1")
                rig.sql.seedTestUser("u1")
                links.linkManually("u1", "book-1", PHM.id, null)

                val answer = answer(lookup(identify = false))

                answer.steps shouldBe setOf(FindStep.TEXT)
                answer.books.none { it.viaLink } shouldBe true
            }
        }

        test("a throttled Hardcover is a rate limit with its retry-after") {
            findTest {
                hardcover.throttleAfterMs = 45_000L
                val failure = source.findBooks(lookup(), MetadataLocale.DEFAULT).shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.ExternalRateLimited>().retryAfterSeconds shouldBe 45L
            }
        }

        test("switched off, Hardcover is unavailable as disabled") {
            findTest {
                sourceSettings.setMetadataEnabled(false)
                source.findAvailability() shouldBe FindAvailability.Unavailable(UnavailableReason.DISABLED)
            }
        }

        test("with no token and no connected account, Hardcover is unavailable as not configured") {
            findTest(withToken = false) {
                source.findAvailability() shouldBe FindAvailability.Unavailable(UnavailableReason.NOT_CONFIGURED)
            }
        }
    })
