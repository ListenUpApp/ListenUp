package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.contributorsDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.test.stubImageStorage
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Covers [com.calypsan.listenup.client.data.sync.domains.contributorsDomain]: Room
 * write-through for SSE contributor events with enrichment copy-forward, alias
 * junction mirroring, and content-addressed image cleanup.
 */
class ContributorsDomainTest :
    FunSpec({

        test("a Created event inserts the contributor row") {
            withHandler { handler, db ->
                handler
                    .onEvent(created(payload("c1", "Brandon Sanderson")))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.contributorDao().getById("c1")
                row shouldNotBe null
                row!!.name shouldBe "Brandon Sanderson"
                row.revision shouldBe 1L
            }
        }

        test("an Updated event preserves enrichment columns the wire does not carry") {
            withHandler { handler, db ->
                db.contributorDao().upsert(
                    ContributorEntity(
                        id = ContributorId("c1"),
                        name = "Old Name",
                        sortName = "Old, Name",
                        description = "A prolific author.",
                        imagePath = "/images/c1.jpg",
                        birthDate = "1975-12-19",
                        createdAt = Timestamp(1L),
                        updatedAt = Timestamp(1L),
                    ),
                )
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "c1",
                        revision = 5,
                        occurredAt = 200L,
                        clientOpId = null,
                        payload = payload("c1", "New Name", revision = 5),
                    ),
                )

                val row = db.contributorDao().getById("c1")!!
                row.name shouldBe "New Name"
                row.revision shouldBe 5L
                row.description shouldBe "A prolific author."
                row.imagePath shouldBe "/images/c1.jpg"
                row.birthDate shouldBe "1975-12-19"
            }
        }

        test("onCatchUpItem with isTombstone soft-deletes the contributor") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("c1", "Brandon Sanderson")))
                handler
                    .onCatchUpItem(payload("c1", "Brandon Sanderson", deletedAt = 100L), isTombstone = true)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.contributorDao().getById("c1")!!.deletedAt shouldBe 100L
            }
        }

        test("a Created event mirrors payload.aliases into the contributor_aliases junction") {
            withHandler { handler, db ->
                handler.onEvent(
                    created(
                        payload(
                            "c1",
                            "Stephen King",
                            aliases = listOf("Richard Bachman", "John Swithen"),
                        ),
                    ),
                )

                db.contributorAliasDao().getForContributor("c1") shouldBe
                    listOf("John Swithen", "Richard Bachman")
            }
        }

        test("an Updated event replaces the existing aliases with the wire list") {
            withHandler { handler, db ->
                handler.onEvent(
                    created(
                        payload(
                            "c1",
                            "Stephen King",
                            aliases = listOf("Richard Bachman", "John Swithen"),
                        ),
                    ),
                )

                handler.onEvent(
                    SyncEvent.Updated(
                        id = "c1",
                        revision = 2,
                        occurredAt = 200L,
                        clientOpId = null,
                        payload = payload("c1", "Stephen King", revision = 2, aliases = listOf("Beryl Evans")),
                    ),
                )

                db.contributorAliasDao().getForContributor("c1") shouldBe listOf("Beryl Evans")
            }
        }

        test("an Updated event with empty aliases clears the junction") {
            withHandler { handler, db ->
                handler.onEvent(
                    created(payload("c1", "Stephen King", aliases = listOf("Richard Bachman"))),
                )

                handler.onEvent(
                    SyncEvent.Updated(
                        id = "c1",
                        revision = 2,
                        occurredAt = 200L,
                        clientOpId = null,
                        payload = payload("c1", "Stephen King", revision = 2, aliases = emptyList()),
                    ),
                )

                db.contributorAliasDao().getForContributor("c1").shouldBeEmpty()
            }
        }

        test("a Deleted event clears the alias junction (soft delete does not cascade)") {
            withHandler { handler, db ->
                handler.onEvent(
                    created(payload("c1", "Stephen King", aliases = listOf("Richard Bachman"))),
                )

                handler.onEvent(
                    SyncEvent.Deleted(
                        id = "c1",
                        revision = 2,
                        occurredAt = 200L,
                        clientOpId = null,
                    ),
                )

                db.contributorDao().getById("c1")!!.deletedAt shouldBe 200L
                db.contributorAliasDao().getForContributor("c1").shouldBeEmpty()
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("c1", "Brandon Sanderson")))
                handler.onEvent(
                    SyncEvent.Deleted(id = "c1", revision = 2, occurredAt = 200L, clientOpId = null),
                )
                // observeById filters tombstones — invisible to reads
                db.contributorDao().observeById("c1").first() shouldBe null
                // and EXCLUDED from the digest — the digest counts live rows only, so a client that
                // tombstoned this row locally converges (F1). Deletions still reach clients via the
                // firehose and the tombstone-ungated access-filtered catch-up. Digest-drift reconciliation
                db.contributorDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "c1"
            }
        }

        test("onCatchUpItem with isTombstone clears the alias junction") {
            withHandler { handler, db ->
                handler.onEvent(
                    created(payload("c1", "Stephen King", aliases = listOf("Richard Bachman"))),
                )

                handler.onCatchUpItem(
                    payload("c1", "Stephen King", revision = 2, deletedAt = 200L),
                    isTombstone = true,
                )

                db.contributorAliasDao().getForContributor("c1").shouldBeEmpty()
            }
        }

        test("handler self-registers under domainName 'contributors'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = contributorsDomain(db, stubImageStorage()).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "contributors"
                registry.lookup("contributors") shouldBe handler
            } finally {
                db.close()
            }
        }

        // The server stores contributor photos content-addressed, so a re-scrape changes imagePath. The
        // local copy is id-named and never otherwise re-downloaded, so it must be dropped on change.
        test("a changed contributor imagePath drops the stale local photo") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val imageStorage =
                        mock<ImageStorage> { everySuspend { deleteContributorImage(any()) } returns AppResult.Success(Unit) }
                    val handler =
                        contributorsDomain(db, imageStorage).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())

                    handler.onEvent(created(photo("c1", "h1")))
                    handler.onEvent(updated(photo("c1", "h2", revision = 2)))

                    verifySuspend(VerifyMode.exactly(1)) { imageStorage.deleteContributorImage("c1") }
                } finally {
                    db.close()
                }
            }
        }

        test("an unchanged contributor imagePath leaves the local photo in place") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val imageStorage =
                        mock<ImageStorage> { everySuspend { deleteContributorImage(any()) } returns AppResult.Success(Unit) }
                    val handler =
                        contributorsDomain(db, imageStorage).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())

                    handler.onEvent(created(photo("c1", "h1")))
                    handler.onEvent(updated(photo("c1", "h1", name = "Renamed", revision = 2)))

                    verifySuspend(VerifyMode.not) { imageStorage.deleteContributorImage(any()) }
                } finally {
                    db.close()
                }
            }
        }
    })

private fun updated(p: ContributorSyncPayload) =
    SyncEvent.Updated(
        id = p.id,
        revision = p.revision,
        occurredAt = p.updatedAt,
        clientOpId = null,
        payload = p,
    )

private fun withHandler(block: suspend (SyncDomainHandler<ContributorSyncPayload>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(
                contributorsDomain(db, stubImageStorage()).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()),
                db,
            )
        } finally {
            db.close()
        }
    }

private fun created(p: ContributorSyncPayload) =
    SyncEvent.Created(
        id = p.id,
        revision = p.revision,
        occurredAt = p.updatedAt,
        clientOpId = null,
        payload = p,
    )

private fun photo(
    id: String,
    hash: String,
    name: String = "Sanderson",
    revision: Long = 1L,
) = payload(id, name, revision = revision).copy(imagePath = "contributors/$hash.jpg")

private fun payload(
    id: String,
    name: String,
    revision: Long = 1L,
    deletedAt: Long? = null,
    aliases: List<String> = emptyList(),
) = ContributorSyncPayload(
    id = id,
    name = name,
    sortName = name,
    revision = revision,
    updatedAt = 100L,
    createdAt = 1L,
    deletedAt = deletedAt,
    aliases = aliases,
)
