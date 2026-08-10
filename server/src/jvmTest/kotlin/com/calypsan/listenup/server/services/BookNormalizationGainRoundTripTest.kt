@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class BookNormalizationGainRoundTripTest :
    FunSpec({

        test("upsert persists normalizationGainDb and reads it back") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeRepo()
                runTest {
                    val payload =
                        bookPayloadFixture(id = "b1", title = "Way of Kings")
                            .copy(normalizationGainDb = -4.5f)

                    val result = repo.upsert(payload, clientOpId = null)
                    result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    result.data.normalizationGainDb shouldBe -4.5f
                }
            }
        }

        test("update does not clobber normalizationGainDb when the payload carries it") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeRepo()
                runTest {
                    val v1 =
                        bookPayloadFixture(id = "b1", title = "Way of Kings")
                            .copy(normalizationGainDb = -4.5f)
                    repo.upsert(v1, clientOpId = null)

                    val v2 =
                        bookPayloadFixture(id = "b1", title = "The Way of Kings")
                            .copy(normalizationGainDb = -4.5f)
                    val result = repo.upsert(v2, clientOpId = null)
                    result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    result.data.normalizationGainDb shouldBe -4.5f
                }
            }
        }
    })

private fun SqlTestDatabases.makeRepo(bus: ChangeBus = ChangeBus()): BookRepository {
    val syncRegistry = SyncRegistry()
    return BookRepository(
        db = sql,
        driver = driver,
        bus = bus,
        registry = syncRegistry,
        contributorRepository = ContributorRepository(sql, bus, syncRegistry),
        seriesRepository = SeriesRepository(sql, bus, syncRegistry),
        genreRepository = GenreRepository(sql, bus, syncRegistry),
    )
}
