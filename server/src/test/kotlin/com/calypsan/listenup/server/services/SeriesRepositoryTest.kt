@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class SeriesRepositoryTest :
    FunSpec({

        test("resolveOrCreate inserts a new series and publishes Created") {
            withInMemoryDatabase {
                val bus = ChangeBus()
                val repo = SeriesRepository(db = this, bus = bus, registry = SyncRegistry())
                runTest {
                    val deferred = async { bus.subscribe().first() }
                    advanceUntilIdle()
                    val id = repo.resolveOrCreate("The Stormlight Archive")
                    val busEvent = deferred.await()
                    busEvent.repo.domainName shouldBe "series"
                    val event = busEvent.event
                    event.shouldBeInstanceOf<SyncEvent.Created<SeriesSyncPayload>>()
                    event.id shouldBe id.value
                    (event as SyncEvent.Created<SeriesSyncPayload>).payload.name shouldBe "The Stormlight Archive"
                }
            }
        }

        test("resolveOrCreate is idempotent on the normalized name") {
            withInMemoryDatabase {
                val repo = SeriesRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val first = repo.resolveOrCreate("The Stormlight Archive")
                    val second = repo.resolveOrCreate("  the   STORMLIGHT archive ")
                    second shouldBe first
                }
            }
        }

        test("idAsString unwraps the value class to its raw string") {
            withInMemoryDatabase {
                val repo = SeriesRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                repo.idAsStringForTest(SeriesId("s1")) shouldBe "s1"
            }
        }
    })
