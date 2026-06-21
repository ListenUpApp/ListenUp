@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.seed.GenreDomainSeeder
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Guard against review-catch **C1**: every canonical slug on the right-hand side of
 * [GenreNormalizer]'s alias map must be owned by an actually-seeded genre.
 *
 * [BookGenreWriter.resolveGenreString] links a book to an aliased genre only when
 * the genre slug returns a row. If an alias points at a slug no seeded genre owns,
 * the link silently no-ops and the resolver auto-creates the *original* raw string as
 * a fresh flat genre — recreating the very duplicate the alias meant to collapse.
 * This test makes that failure mode impossible to ship: it seeds the real default
 * taxonomy and asserts the alias targets are a subset of the seeded slugs.
 */
class GenreAliasSeededTargetsTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_730_000_000_000L))

        test("every alias target slug is owned by a seeded genre") {
            withSqlDatabase {
                runTest {
                    val seeder =
                        GenreDomainSeeder(
                            genreRepository =
                                GenreRepository(sql, ChangeBus(), SyncRegistry(), fixedClock),
                            clock = fixedClock,
                        )
                    seeder.seed()

                    val seededSlugs =
                        sql.genresQueries
                            .listLiveOrderedByPath()
                            .executeAsList()
                            .map { it.slug }
                            .toSet()

                    val danglingTargets = GenreNormalizer.canonicalSlugs() - seededSlugs

                    // Empty means no alias silently no-ops into an auto-created duplicate.
                    danglingTargets.shouldBeEmpty()
                }
            }
        }

        test("Thriller & Suspense and Suspense collapse to the same canonical genre") {
            // Both forms must resolve to one logical genre — no near-duplicate.
            val thrillerAndSuspense = GenreNormalizer.normalizeToSlugs("Thriller & Suspense")
            val suspense = GenreNormalizer.normalizeToSlugs("Suspense")

            thrillerAndSuspense shouldBe listOf("mystery-thriller")
            suspense shouldBe listOf("mystery-thriller")
            thrillerAndSuspense shouldBe suspense
        }
    })
