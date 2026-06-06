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
                    val id = repo.resolveOrCreate("Brandon Sanderson", sortName = null)
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
                    val first = repo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    val second = repo.resolveOrCreate("  brandon   SANDERSON ", sortName = null)
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
                    repo.resolveOrCreate("Author One", sortName = null)
                    repo.resolveOrCreate("Author Two", sortName = null)
                    val page = repo.pullSince(userId = null, cursor = 0L, limit = 10)
                    page.items.map { it.name } shouldBe listOf("Author One", "Author Two")
                }
            }
        }

        test("display-order variants of the same name resolve to one contributor") {
            withInMemoryDatabase {
                val repo = ContributorRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val id1 = repo.resolveOrCreate("Brandon Sanderson", sortName = "Sanderson, Brandon")
                    val id2 = repo.resolveOrCreate("Sanderson, Brandon", sortName = "Sanderson, Brandon")
                    id2 shouldBe id1
                }
            }
        }

        test("first display name wins; later variant does not overwrite it") {
            withInMemoryDatabase {
                val repo = ContributorRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val id = repo.resolveOrCreate("Brandon Sanderson", sortName = "Sanderson, Brandon")
                    repo.resolveOrCreate("Sanderson, Brandon", sortName = "Sanderson, Brandon")
                    repo.findById(id.value)?.name shouldBe "Brandon Sanderson"
                }
            }
        }

        test("null sortName derives the sort form as the key — same person found on second call") {
            withInMemoryDatabase {
                val repo = ContributorRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val a = repo.resolveOrCreate("Unique Person", sortName = null)
                    val b = repo.resolveOrCreate("Unique Person", sortName = null)
                    b shouldBe a
                }
            }
        }

        test("manual (null sortName) and scanner (derived sortName) paths converge to one row") {
            withInMemoryDatabase {
                val repo = ContributorRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    // Simulates BookServiceImpl / BookMetadataApplier (null sortName — repo derives)
                    val manual = repo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    // Simulates the scanner (explicit derived sort name)
                    val scanner = repo.resolveOrCreate("Brandon Sanderson", sortName = "Sanderson, Brandon")
                    scanner shouldBe manual
                }
            }
        }
    })
