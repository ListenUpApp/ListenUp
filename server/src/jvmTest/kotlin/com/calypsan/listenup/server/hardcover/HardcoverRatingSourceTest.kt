package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.admin.RatingSourceUnavailable
import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.ExternalRatingMeta
import com.calypsan.listenup.server.metadata.spi.RatingSourceAvailability
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList

private val RATING_JSON = headersOf(HttpHeaders.ContentType, "application/json")
private val LOCALE = MetadataLocale("en", "us")

private fun ratingFixture(name: String): String =
    checkNotNull(HardcoverRatingSourceTest::class.java.getResource("/hardcover/$name")) { "missing fixture $name" }.readText()

private val MISSING = ratingFixture("rating-by-asin-missing.json")
private val FOUND_BY_ASIN = ratingFixture("rating-by-asin-found.json")
private val FOUND_BY_ISBN = ratingFixture("rating-by-isbn-found.json")
private val PAGEANT_CANDIDATES = ratingFixture("rating-by-title-author.json")

private val PAGEANT = BookIdentity(title = "The Best Christmas Pageant Ever", primaryAuthor = "Barbara Robinson")

/** One request the fake Hardcover saw: which lookup, and whose token asked. */
private data class Asked(
    val lookup: String,
    val token: String,
)

/** A scripted Hardcover GraphQL endpoint: one canned reply per lookup kind, and a log of who asked what. */
private class FakeRatingGraphQl {
    val asked = CopyOnWriteArrayList<Asked>()
    var byAsin: Pair<HttpStatusCode, String> = HttpStatusCode.OK to MISSING
    var byIsbn: Pair<HttpStatusCode, String> = HttpStatusCode.OK to MISSING
    var byTitle: Pair<HttpStatusCode, String> = HttpStatusCode.OK to """{"data":{"books":[]}}"""

    /** Tokens Hardcover answers 401 for, whatever is asked. */
    var rejected: Set<String> = emptySet()

    val client =
        HardcoverGraphQlClient(
            http =
                HttpClient(
                    MockEngine { req ->
                        val query = (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                        val (lookup, reply) =
                            when {
                                "asin:{_eq" in query -> "asin" to byAsin
                                "isbn_13" in query -> "isbn" to byIsbn
                                else -> "title" to byTitle
                            }
                        val token = req.headers[HttpHeaders.Authorization].orEmpty().removePrefix("Bearer ")
                        asked += Asked(lookup, token)
                        if (token in rejected) {
                            respond("""{"error":"invalid_token"}""", HttpStatusCode.Unauthorized, RATING_JSON)
                        } else {
                            respond(reply.second, reply.first, RATING_JSON)
                        }
                    },
                ),
            apiBaseUrl = "https://hc.test",
        )

    val lookups get() = asked.map { it.lookup }
}

private class NoWait : HardcoverRateLimiter() {
    override suspend fun await() = Unit
}

private fun ratingCipher(secret: String) = HardcoverTokenCipher(HardcoverTokenCipher.deriveKey(secret))

/**
 * The source over a real connection store and token provider; the OAuth endpoint is never reached
 * because every seeded access token is fresh. Tokens are `at-<userId>`, so a request's bearer names
 * whose connection was borrowed.
 */
private class RatingRig(
    val sql: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase,
    clientConfigured: Boolean = true,
) {
    val store = HardcoverConnectionStore(sql, ratingCipher("secret"))
    val graphQl = FakeRatingGraphQl()
    private val oauth =
        HardcoverOAuthClient(HttpClient(MockEngine { respond("{}", HttpStatusCode.InternalServerError) }), "id", "https://hc.test")
    private val linker =
        HardcoverLinker(oauth, graphQl.client, store, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
    val connection = HardcoverRatingConnection(store, HardcoverTokenProvider(oauth, store, linker))
    val apiTokens = HardcoverApiTokenStore(sql, ratingCipher("secret"))
    val source = HardcoverRatingSource(graphQl.client, HardcoverCatalogToken(apiTokens, connection), NoWait(), clientConfigured)

    suspend fun connect(
        userId: String,
        role: UserRoleColumn = UserRoleColumn.MEMBER,
        via: HardcoverConnectionStore = store,
    ) {
        sql.seedTestUser(userId, role)
        via.save(userId, HardcoverMe(1, "hc-$userId"), HardcoverTokens("at-$userId", "rt-$userId", 604_800, "scope"))
    }

    suspend fun rating(book: BookIdentity) = source.getRating(book, LOCALE)
}

private fun ratingTest(
    clientConfigured: Boolean = true,
    block: suspend RatingRig.() -> Unit,
) = withSqlDatabase { runTest { RatingRig(sql, clientConfigured).block() } }

/** Hardcover as a [com.calypsan.listenup.server.metadata.spi.RatingSource], on a connected account. */
class HardcoverRatingSourceTest :
    FunSpec({

        test("without a client id the source is NOT_CONFIGURED, even with someone connected") {
            ratingTest(clientConfigured = false) {
                connect("admin", UserRoleColumn.ADMIN)
                source.availability() shouldBe
                    RatingSourceAvailability.Unavailable(RatingSourceUnavailable.NOT_CONFIGURED)
            }
        }

        test("with a client id but nobody connected the source is NO_CONNECTION") {
            ratingTest {
                source.availability() shouldBe RatingSourceAvailability.Unavailable(RatingSourceUnavailable.NO_CONNECTION)
            }
        }

        test("a connection marked broken does not count as connected") {
            ratingTest {
                connect("member")
                store.markBroken("member", HardcoverBrokenReason.REVOKED)
                source.availability() shouldBe RatingSourceAvailability.Unavailable(RatingSourceUnavailable.NO_CONNECTION)
            }
        }

        test("a connected account makes the source available") {
            ratingTest {
                connect("member")
                source.availability() shouldBe RatingSourceAvailability.Available
            }
        }

        test("connections are preferred ROOT, then ADMIN, then MEMBER, and the pick names the Hardcover account") {
            ratingTest {
                connect("member", UserRoleColumn.MEMBER)
                connect("admin", UserRoleColumn.ADMIN)
                connect("root", UserRoleColumn.ROOT)
                store.healthyUserIds() shouldBe listOf("root", "admin", "member")
                connection.pick() shouldBe PickedHardcoverConnection("root", "hc-root")
            }
        }

        test("with an admin and a member both connected, the admin's token asks") {
            ratingTest {
                connect("member")
                connect("admin", UserRoleColumn.ADMIN)
                graphQl.byAsin = HttpStatusCode.OK to FOUND_BY_ASIN
                rating(BookIdentity(asin = "B08G9RZBTT", title = "Project Hail Mary"))
                graphQl.asked.map { it.token } shouldBe listOf("at-admin")
            }
        }

        test("with only a member connected, the member's token asks") {
            ratingTest {
                connect("member")
                graphQl.byAsin = HttpStatusCode.OK to FOUND_BY_ASIN
                rating(BookIdentity(asin = "B08G9RZBTT", title = "Project Hail Mary"))
                graphQl.asked.map { it.token } shouldBe listOf("at-member")
            }
        }

        test("an ASIN hit is the rating, with no region, and nothing else is asked") {
            ratingTest {
                connect("member")
                graphQl.byAsin = HttpStatusCode.OK to FOUND_BY_ASIN
                val result = rating(BookIdentity(asin = "B08G9RZBTT", isbn = "9780593135204", title = "Project Hail Mary"))
                val meta = result.shouldBeInstanceOf<AppResult.Success<ExternalRatingMeta?>>().data!!
                meta.count shouldBe 8107
                meta.region shouldBe null
                graphQl.lookups shouldBe listOf("asin")
            }
        }

        test("an ASIN miss falls through to the ISBN") {
            ratingTest {
                connect("member")
                graphQl.byIsbn = HttpStatusCode.OK to FOUND_BY_ISBN
                val result = rating(BookIdentity(asin = "B072HRZ7LD", isbn = "9780593135204", title = "Project Hail Mary"))
                result.shouldBeInstanceOf<AppResult.Success<ExternalRatingMeta?>>().data!!.count shouldBe 8107
                graphQl.lookups shouldBe listOf("asin", "isbn")
            }
        }

        test("an ISBN miss falls through to the title, and the most-rated confident match wins") {
            ratingTest {
                connect("member")
                graphQl.byTitle = HttpStatusCode.OK to PAGEANT_CANDIDATES
                val result = rating(PAGEANT.copy(asin = "B072HRZ7LD", isbn = "9780000000000"))
                val meta = result.shouldBeInstanceOf<AppResult.Success<ExternalRatingMeta?>>().data!!
                // Two Barbara Robinson records (58 and 2 ratings); a third by another author is no match.
                meta.count shouldBe 58
                meta.average shouldBe 4.2586206896551724
                graphQl.lookups shouldBe listOf("asin", "isbn", "title")
            }
        }

        test("the most-rated confident match wins even when Hardcover lists it second") {
            ratingTest {
                connect("member")
                val reversed =
                    """{"data":{"books":[{"id":1,"title":"The Best Christmas Pageant Ever","rating":3.5,"ratings_count":2,""" +
                        """"contributions":[{"author":{"name":"Barbara Robinson"}}]},{"id":2,"title":"The Best Christmas Pageant Ever",""" +
                        """"rating":4.25,"ratings_count":58,"contributions":[{"author":{"name":"Barbara Robinson"}}]}]}}"""
                graphQl.byTitle = HttpStatusCode.OK to reversed
                rating(PAGEANT).shouldBeInstanceOf<AppResult.Success<ExternalRatingMeta?>>().data!!.count shouldBe 58
            }
        }

        test("title candidates that are not confident matches give no rating, not a guess") {
            ratingTest {
                connect("member")
                graphQl.byTitle = HttpStatusCode.OK to PAGEANT_CANDIDATES
                rating(PAGEANT.copy(primaryAuthor = "Someone Else Entirely")) shouldBe AppResult.Success(null)
                rating(PAGEANT.copy(primaryAuthor = null)) shouldBe AppResult.Success(null)
            }
        }

        test("a book Hardcover has no record of is Success(null)") {
            ratingTest {
                connect("member")
                rating(PAGEANT.copy(asin = "B072HRZ7LD")) shouldBe AppResult.Success(null)
            }
        }

        test("a connection whose tokens no longer decrypt is a Failure, which counts toward backoff") {
            ratingTest {
                connect("member", via = HardcoverConnectionStore(sql, ratingCipher("another-secret")))
                val failure = rating(PAGEANT).shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<HardcoverError.ConnectionBroken>()
                graphQl.asked.size shouldBe 0
            }
        }

        test("Hardcover rejecting the token is a Failure") {
            ratingTest {
                connect("member")
                graphQl.byAsin = HttpStatusCode.Unauthorized to ""
                rating(PAGEANT.copy(asin = "B08G9RZBTT"))
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<HardcoverError.ConnectionBroken>()
            }
        }

        test("Hardcover being unreachable is a Failure") {
            ratingTest {
                connect("member")
                graphQl.byTitle = HttpStatusCode.InternalServerError to "oops"
                rating(PAGEANT)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<HardcoverError.Unavailable>()
            }
        }

        test("a connection that vanishes after the availability check is Success(null)") {
            ratingTest {
                connect("member")
                source.availability() shouldBe RatingSourceAvailability.Available
                store.delete("member")
                rating(PAGEANT) shouldBe AppResult.Success(null)
                graphQl.asked.size shouldBe 0
            }
        }

        test("when the preferred connection is unusable, the next one's token is tried") {
            ratingTest {
                connect("admin", UserRoleColumn.ADMIN, via = HardcoverConnectionStore(sql, ratingCipher("another-secret")))
                connect("member")
                graphQl.byTitle = HttpStatusCode.OK to PAGEANT_CANDIDATES
                rating(PAGEANT).shouldBeInstanceOf<AppResult.Success<ExternalRatingMeta?>>().data!!.count shouldBe 58
                graphQl.asked.map { it.token }.distinct() shouldBe listOf("at-member")
            }
        }

        test("with an API token the source is available even without a client id or a connection") {
            ratingTest(clientConfigured = false) {
                apiTokens.save(ADMIN_TOKEN, "simon")
                source.availability() shouldBe RatingSourceAvailability.Available
            }
        }

        test("with an API token, the API token asks before any connected account") {
            ratingTest {
                connect("admin", UserRoleColumn.ADMIN)
                apiTokens.save(ADMIN_TOKEN, "simon")
                graphQl.byAsin = HttpStatusCode.OK to FOUND_BY_ASIN

                rating(BookIdentity(asin = "B08G9RZBTT", title = "Project Hail Mary"))

                graphQl.asked.map { it.token } shouldBe listOf(ADMIN_TOKEN)
            }
        }

        test("a 401 on the API token marks it rejected, and the rating comes through a connected account") {
            ratingTest {
                connect("admin", UserRoleColumn.ADMIN)
                apiTokens.save(ADMIN_TOKEN, "simon")
                graphQl.rejected = setOf(ADMIN_TOKEN)
                graphQl.byAsin = HttpStatusCode.OK to FOUND_BY_ASIN

                val result = rating(BookIdentity(asin = "B08G9RZBTT", title = "Project Hail Mary"))

                result.shouldBeInstanceOf<AppResult.Success<ExternalRatingMeta?>>().data!!.count shouldBe 8107
                graphQl.asked.map { it.token } shouldBe listOf(ADMIN_TOKEN, "at-admin")
                apiTokens.status() shouldBe
                    com.calypsan.listenup.api.dto.admin.HardcoverApiTokenStatus
                        .Rejected("simon")
            }
        }

        test("a 401 on a connected account's token is still a broken connection") {
            ratingTest {
                connect("member")
                graphQl.rejected = setOf("at-member")

                val result = rating(BookIdentity(asin = "B08G9RZBTT", title = "Project Hail Mary"))

                result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<HardcoverError.ConnectionBroken>()
            }
        }
    })
