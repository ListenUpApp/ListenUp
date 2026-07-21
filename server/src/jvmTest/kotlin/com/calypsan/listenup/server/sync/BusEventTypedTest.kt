@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Verifies the type-binding invariant of [BusEvent]: the source repository
 * travels with the event so the sync firehose can encode the payload through
 * the matching serializer without a static-registry lookup.
 */
class BusEventTypedTest :
    FunSpec({

        test("BusEvent.repo provides the serializer needed to encode the event payload") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = sql, bus = bus, registry = SyncRegistry())

                runTest {
                    val deferredBusEvent = async { bus.subscribe().first() }
                    advanceUntilIdle()

                    repo.upsert(Tag(id = "t1", name = "sci-fi", slug = "sci-fi", revision = 0, updatedAt = 0))

                    val busEvent = deferredBusEvent.await()

                    // Type-binding invariant: the repo travels with the event.
                    busEvent.repo.shouldNotBeNull()
                    busEvent.repo.domainName shouldBe "tags"

                    // The repo's own serializer can encode the matching event.
                    val json = busEvent.repo.encodeSyncEventAsJson(busEvent.event)
                    json shouldContain """"id":"t1""""
                    json shouldContain "Created"
                }
            }
        }
    })
