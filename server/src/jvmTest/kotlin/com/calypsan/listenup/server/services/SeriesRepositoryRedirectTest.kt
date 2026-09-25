@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * The series merge redirect: a series tombstoned by a merge points at the series it was merged
 * into, and name resolution follows that pointer instead of reviving the merged-away row.
 */
class SeriesRepositoryRedirectTest :
    FunSpec({

        test("resolveOrCreate follows a merge redirect instead of reviving the merged-away series") {
            withSqlDatabase {
                val repo = SeriesRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    val source = repo.resolveOrCreate("Wheel of Time")
                    val target = repo.resolveOrCreate("The Wheel of Time")
                    repo.softDeleteMergedInto(source, target).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    repo.resolveOrCreate("Wheel of Time") shouldBe target
                    repo.findById(source.value).shouldNotBeNull().deletedAt.shouldNotBeNull()
                }
            }
        }

        test("resolveOrCreateAll follows merge redirects in the batch path") {
            withSqlDatabase {
                val repo = SeriesRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    val source = repo.resolveOrCreate("Wheel of Time")
                    val target = repo.resolveOrCreate("The Wheel of Time")
                    repo.softDeleteMergedInto(source, target)

                    val resolved = repo.resolveOrCreateAll(listOf("Wheel of Time"))

                    resolved.values.single() shouldBe target
                    repo.findById(source.value).shouldNotBeNull().deletedAt.shouldNotBeNull()
                }
            }
        }

        test("a redirect chain resolves to its first live series, and moves when the middle is revived") {
            withSqlDatabase {
                val repo = SeriesRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    val c = repo.resolveOrCreate("Series C")
                    val a = repo.resolveOrCreate("Series A")
                    val b = repo.resolveOrCreate("Series B")
                    repo.softDeleteMergedInto(c, a)
                    repo.softDeleteMergedInto(a, b)

                    repo.resolveOrCreate("Series C") shouldBe b

                    repo.revive(a).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    repo.resolveOrCreate("Series C") shouldBe a
                }
            }
        }

        test("reviving a merged-away series clears its redirect") {
            withSqlDatabase {
                val repo = SeriesRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    val source = repo.resolveOrCreate("Wheel of Time")
                    val target = repo.resolveOrCreate("The Wheel of Time")
                    repo.softDeleteMergedInto(source, target)

                    repo.revive(source)

                    sql.seriesQueries.selectById(source.value).executeAsOne().merged_into.shouldBeNull()
                    repo.resolveOrCreate("Wheel of Time") shouldBe source
                }
            }
        }

        test("a dead redirect falls back to reviving the original series") {
            withSqlDatabase {
                val repo = SeriesRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    val source = repo.resolveOrCreate("Wheel of Time")
                    val target = repo.resolveOrCreate("The Wheel of Time")
                    repo.softDeleteMergedInto(source, target)
                    repo.softDelete(target)

                    repo.resolveOrCreate("Wheel of Time") shouldBe source
                    repo.findById(source.value).shouldNotBeNull().deletedAt.shouldBeNull()
                }
            }
        }
    })
