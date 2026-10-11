@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.matching.FIND_DEADLINE
import com.calypsan.listenup.server.matching.PeopleSubject
import com.calypsan.listenup.server.metadata.audnexus.AudnexusAuthor
import com.calypsan.listenup.server.metadata.audnexus.AudnexusBook
import com.calypsan.listenup.server.metadata.audnexus.AudnexusRateLimiter
import com.calypsan.listenup.server.metadata.spi.ContributorHitMeta
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.PersonAnswer
import com.calypsan.listenup.server.metadata.spi.PersonLibraryBook
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Instant

private const val RUOCCHIO = "B01RUOCCHIO"
private const val RUOCCHIO_UK = "B02RUOCCHIO"
private const val RUOCCHIO_TYPO = "B03RUOCCHIO"
private const val DAVIS = "B0HANKDAVIS"

/** The rate limiter's clock, read off the test's virtual time. */
private fun TestScope.virtualClock(): Clock =
    object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(currentTime)
    }

/** Christopher Ruocchio, linked at Audible, with nine books here — two of them anthologies co-edited with Hank Davis. */
private fun ruocchio(): PeopleSubject =
    PeopleSubject(
        contributorId = "c-ruocchio",
        name = "Christopher Ruocchio",
        refs = listOf(ExternalRef(ExternalRef.AUDIBLE, RUOCCHIO)),
        books =
            (1..9).map { n ->
                PersonLibraryBook(
                    bookId = "b$n",
                    title = "Sun Eater $n",
                    asin = "A$n",
                    isbn = null,
                    refs = emptyList(),
                    roles = setOf(ContributorRole.AUTHOR),
                )
            },
    )

private fun credits(asin: String): AudnexusBook =
    AudnexusBook(
        asin = asin,
        authors =
            listOfNotNull(
                AudnexusAuthor(RUOCCHIO, "Christopher Ruocchio"),
                AudnexusAuthor(DAVIS, "Hank Davis").takeIf { asin in setOf("A1", "A2") },
            ),
    )

/**
 * The time budget of an Audible people Find (production v0.9.8: Audible "almost always fails the first time").
 * Every Audnexus read waits its turn at the real one-a-second [AudnexusRateLimiter]; the answers themselves are
 * instant, so the elapsed virtual time is the rate limiter's alone.
 */
class AudnexusPeopleBudgetTest :
    FunSpec({
        test("a linked author with nine books and a three-hit name search answers within the Find deadline on a cold cache") {
            runTest {
                val limiter = AudnexusRateLimiter(clock = virtualClock())
                var calls = 0
                val people =
                    AudnexusPeople(
                        search = { _ ->
                            limiter.await()
                            calls++
                            AppResult.Success(
                                listOf(
                                    ContributorHitMeta(RUOCCHIO, "Christopher Ruocchio"),
                                    ContributorHitMeta(RUOCCHIO_UK, "Christopher Ruocchio"),
                                    ContributorHitMeta(RUOCCHIO_TYPO, "Christopher Ruochio"),
                                ),
                            )
                        },
                        book = { asin ->
                            limiter.await()
                            calls++
                            AppResult.Success(credits(asin))
                        },
                        cachedBook = { null },
                        profile = { key ->
                            limiter.await()
                            calls++
                            AppResult.Success(ContributorMeta(key = key, name = "", imageUrl = "https://a/$key.jpg"))
                        },
                    )

                val answer =
                    withTimeoutOrNull(FIND_DEADLINE) {
                        people.find(ruocchio().lookupFor(MetadataProviderId.AUDNEXUS, query = null))
                    }

                val found =
                    answer
                        .shouldNotBeNull()
                        .shouldBeInstanceOf<AppResult.Success<PersonAnswer>>()
                        .data.people
                        .associateBy { it.key }
                found.keys shouldBe setOf(RUOCCHIO, RUOCCHIO_UK, RUOCCHIO_TYPO, DAVIS)
                found.getValue(RUOCCHIO).viaLink shouldBe true
                found.getValue(RUOCCHIO).photoUrl shouldBe "https://a/$RUOCCHIO.jpg"
                found.getValue(DAVIS).name shouldBe "Hank Davis"
                calls shouldBe 6
            }
        }
    })
