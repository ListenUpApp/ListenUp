@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.ContributorId
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

class ContributorRepositoryTest :
    FunSpec({

        test("resolveOrCreate inserts a new contributor and publishes Created") {
            withInMemoryDatabase {
                val bus = ChangeBus()
                val repo = ContributorRepository(db = this, bus = bus, registry = SyncRegistry())
                runTest {
                    val deferred = async { bus.subscribe().first() }
                    advanceUntilIdle()
                    val id = repo.resolveOrCreate("Brandon Sanderson")
                    val busEvent = deferred.await()
                    busEvent.repo.domainName shouldBe "contributors"
                    val event = busEvent.event
                    event.shouldBeInstanceOf<SyncEvent.Created<ContributorSyncPayload>>()
                    event.id shouldBe id.value
                    (event as SyncEvent.Created<ContributorSyncPayload>).payload.name shouldBe "Brandon Sanderson"
                    event.revision shouldBe 1L
                }
            }
        }

        test("resolveOrCreate is idempotent on the normalized name — no second event") {
            withInMemoryDatabase {
                val repo = ContributorRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val first = repo.resolveOrCreate("Brandon Sanderson")
                    val second = repo.resolveOrCreate("  brandon   SANDERSON ")
                    second shouldBe first
                    repo.findById(first.value)?.revision shouldBe 1L
                }
            }
        }

        test("idAsString unwraps the value class to its raw string") {
            withInMemoryDatabase {
                val repo = ContributorRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                repo.idAsStringForTest(ContributorId("c1")) shouldBe "c1"
            }
        }

        test("pullSince returns inserted contributors in revision order") {
            withInMemoryDatabase {
                val repo = ContributorRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.resolveOrCreate("Author One")
                    repo.resolveOrCreate("Author Two")
                    val page = repo.pullSince(userId = null, cursor = 0L, limit = 10)
                    page.items.map { it.name } shouldBe listOf("Author One", "Author Two")
                }
            }
        }
    })
