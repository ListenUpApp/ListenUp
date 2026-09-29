package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.api.sync.ExternalRatingSyncPayload
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class BookExternalRatingsDomainTest :
    FunSpec({
        test("an upsert mirrors the row and a Deleted frame by wire id tombstones it") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val apply = BookExternalRatingMirrorApply(db)
                    apply.upsert(
                        ExternalRatingSyncPayload(
                            id = "e1",
                            bookId = "b1",
                            source = ExternalRatingSource.AUDIBLE,
                            average = 4.4,
                            count = 812,
                            enabled = true,
                            revision = 5L,
                        ),
                    )
                    db
                        .bookExternalRatingDao()
                        .observeForBook("b1")
                        .first()
                        .single()
                        .average shouldBe 4.4

                    apply.tombstoneById("e1", deletedAt = 9L)

                    db.bookExternalRatingDao().observeForBook("b1").first() shouldBe emptyList()
                }
            } finally {
                db.close()
            }
        }

        test("a row whose source this build doesn't recognise mirrors as UNKNOWN without crashing") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val apply = BookExternalRatingMirrorApply(db)
                    apply.upsert(
                        ExternalRatingSyncPayload(
                            id = "e2",
                            bookId = "b2",
                            source = ExternalRatingSource.UNKNOWN,
                            average = 3.9,
                            count = 40,
                            enabled = true,
                            revision = 1L,
                        ),
                    )

                    val stored =
                        db
                            .bookExternalRatingDao()
                            .observeForBook("b2")
                            .first()
                            .single()
                    stored.source shouldBe "UNKNOWN"
                    stored.average shouldBe 3.9
                }
            } finally {
                db.close()
            }
        }

        test("a catch-up tombstone applies by wire id, since the payload carries no bookId/source") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val apply = BookExternalRatingMirrorApply(db)
                    apply.upsert(
                        ExternalRatingSyncPayload(
                            id = "e3",
                            bookId = "b3",
                            source = ExternalRatingSource.AUDIBLE,
                            average = 4.0,
                            count = 5,
                            enabled = true,
                            revision = 1L,
                        ),
                    )

                    // A tombstone ships with bookId blanked (BookExternalRatingRepository.minimizeTombstone).
                    apply.tombstoneFromItem(
                        ExternalRatingSyncPayload(
                            id = "e3",
                            bookId = "",
                            source = ExternalRatingSource.AUDIBLE,
                            average = 4.0,
                            count = 5,
                            enabled = true,
                            revision = 2L,
                            deletedAt = 10L,
                        ),
                    )

                    db.bookExternalRatingDao().observeForBook("b3").first() shouldBe emptyList()
                    db.bookExternalRatingDao().revisionOfSyncId("e3").shouldNotBeNull()
                }
            } finally {
                db.close()
            }
        }

        test("the domain is access-gated, like every book-scoped domain") {
            val db = createInMemoryTestDatabase()
            try {
                bookExternalRatingsDomain(db).accessGate.shouldNotBeNull()
            } finally {
                db.close()
            }
        }

        test("the domain has no outbox — the server is the sole writer") {
            val db = createInMemoryTestDatabase()
            try {
                bookExternalRatingsDomain(db).writes shouldBe WriteTier.ServerOwned
            } finally {
                db.close()
            }
        }
    })
