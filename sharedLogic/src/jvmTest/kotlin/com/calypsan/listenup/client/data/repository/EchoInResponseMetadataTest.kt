package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.api.sync.Mutated
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncFrameApplier
import com.calypsan.listenup.client.data.sync.domains.contributorsDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.core.ContributorId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * End-to-end proof of echo-in-response at the repository seam: a contributor-metadata scrape returns
 * a [Mutated] envelope, and [MetadataRepositoryImpl] — through `RpcChannel.callMutation` and the
 * real [SyncFrameApplier] → real contributors handler → real Room — reflects the server's change in
 * the ORIGINATING client's database.
 *
 * There is deliberately **no firehose, dispatcher, or sync engine** in this wiring: the only way the
 * contributor row can appear in Room is the response-carried frame. That is exactly the gap the
 * design closes — the device that made the call used to miss its own live echo and stay stale.
 */
class EchoInResponseMetadataTest :
    FunSpec({

        test("applyContributorMetadata applies the response frame into the originating client's Room") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val registry = ClientSyncDomainRegistry()
                    contributorsDomain(db, stubImageStorage()).toHandler(RoomTransactionRunner(db), registry)
                    val applier = SyncFrameApplier(registry)

                    // The frame the server builds for the scraped contributor — enriched asin + bio + photo.
                    val enriched =
                        ContributorSyncPayload(
                            id = "c1",
                            name = "Brandon Sanderson",
                            sortName = "Sanderson, Brandon",
                            revision = 7L,
                            updatedAt = 100L,
                            createdAt = 1L,
                            deletedAt = null,
                            asin = "B0ASIN",
                            description = "Enriched bio.",
                            imagePath = "contributors/abc123.jpg",
                        )
                    val frame =
                        SyncFrame(
                            domain = "contributors",
                            revision = 7L,
                            json =
                                contractJson.encodeToString(
                                    SyncEvent.serializer(ContributorSyncPayload.serializer()),
                                    SyncEvent.Updated(
                                        id = "c1",
                                        revision = 7L,
                                        occurredAt = 100L,
                                        clientOpId = null,
                                        payload = enriched,
                                    ),
                                ),
                        )

                    val service =
                        mock<MetadataLookupService> {
                            everySuspend {
                                applyContributorMetadata(ContributorId("c1"), "B0ASIN", MetadataLocale("us"))
                            } returns AppResult.Success(Mutated(Unit, listOf(frame)))
                        }
                    val channel: RpcChannel<MetadataLookupService> = RpcChannel.forTest(service, frameApplier = applier)
                    val repo = MetadataRepositoryImpl(channel)

                    // Room starts empty — no firehose ever runs in this test.
                    db.contributorDao().getById("c1") shouldBe null

                    val result = repo.applyContributorMetadata(ContributorId("c1"), "B0ASIN", MetadataLocale("us"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    // The originating client now sees the scrape's result — applied purely from the response.
                    val row = db.contributorDao().getById("c1")
                    row.shouldNotBeNull()
                    row.asin shouldBe "B0ASIN"
                    row.description shouldBe "Enriched bio."
                    row.imagePath shouldBe "contributors/abc123.jpg"
                    row.revision shouldBe 7L
                } finally {
                    db.close()
                }
            }
        }
    })
