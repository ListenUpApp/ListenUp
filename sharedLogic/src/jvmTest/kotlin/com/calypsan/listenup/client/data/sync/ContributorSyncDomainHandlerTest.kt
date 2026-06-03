package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.handlers.ContributorSyncDomainHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class ContributorSyncDomainHandlerTest :
    FunSpec({

        test("a Created event inserts the contributor row") {
            withHandler { handler, db ->
                handler
                    .onEvent(created(payload("c1", "Brandon Sanderson")), isOwnEcho = false)
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
                    isOwnEcho = false,
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
                handler.onEvent(created(payload("c1", "Brandon Sanderson")), isOwnEcho = false)
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
                    isOwnEcho = false,
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
                    isOwnEcho = false,
                )

                handler.onEvent(
                    SyncEvent.Updated(
                        id = "c1",
                        revision = 2,
                        occurredAt = 200L,
                        clientOpId = null,
                        payload = payload("c1", "Stephen King", revision = 2, aliases = listOf("Beryl Evans")),
                    ),
                    isOwnEcho = false,
                )

                db.contributorAliasDao().getForContributor("c1") shouldBe listOf("Beryl Evans")
            }
        }

        test("an Updated event with empty aliases clears the junction") {
            withHandler { handler, db ->
                handler.onEvent(
                    created(payload("c1", "Stephen King", aliases = listOf("Richard Bachman"))),
                    isOwnEcho = false,
                )

                handler.onEvent(
                    SyncEvent.Updated(
                        id = "c1",
                        revision = 2,
                        occurredAt = 200L,
                        clientOpId = null,
                        payload = payload("c1", "Stephen King", revision = 2, aliases = emptyList()),
                    ),
                    isOwnEcho = false,
                )

                db.contributorAliasDao().getForContributor("c1").shouldBeEmpty()
            }
        }

        test("a Deleted event clears the alias junction (soft delete does not cascade)") {
            withHandler { handler, db ->
                handler.onEvent(
                    created(payload("c1", "Stephen King", aliases = listOf("Richard Bachman"))),
                    isOwnEcho = false,
                )

                handler.onEvent(
                    SyncEvent.Deleted(
                        id = "c1",
                        revision = 2,
                        occurredAt = 200L,
                        clientOpId = null,
                    ),
                    isOwnEcho = false,
                )

                db.contributorDao().getById("c1")!!.deletedAt shouldBe 200L
                db.contributorAliasDao().getForContributor("c1").shouldBeEmpty()
            }
        }

        test("onCatchUpItem with isTombstone clears the alias junction") {
            withHandler { handler, db ->
                handler.onEvent(
                    created(payload("c1", "Stephen King", aliases = listOf("Richard Bachman"))),
                    isOwnEcho = false,
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
                val handler = ContributorSyncDomainHandler(db, RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "contributors"
                registry.lookup("contributors") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

private fun withHandler(block: suspend (ContributorSyncDomainHandler, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(ContributorSyncDomainHandler(db, RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
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
