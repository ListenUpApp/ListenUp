package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Covers the per-domain sync cursor table introduced in Phase B of the client
 * sync renovation. The cursor records the highest revision the client has fully
 * applied per domain so reconnect can resume via `Last-Event-Id` instead of
 * falling back to REST catch-up every time.
 */
class SyncCursorDaoTest :
    FunSpec({

        test("getCursor returns null for an unknown domain") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    db.syncCursorDao().getCursor("nope") shouldBe null
                } finally {
                    db.close()
                }
            }
        }

        test("setCursor + getCursor round-trip") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val dao = db.syncCursorDao()
                    val expected = 42L
                    dao.setCursor(SyncCursorEntity(domainName = "tags", revision = expected))
                    dao.getCursor("tags") shouldBe expected
                } finally {
                    db.close()
                }
            }
        }

        test("setCursor overwrites an existing row for the same domain") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val dao = db.syncCursorDao()
                    val initial = 1L
                    val updated = 99L
                    dao.setCursor(SyncCursorEntity(domainName = "tags", revision = initial))
                    dao.setCursor(SyncCursorEntity(domainName = "tags", revision = updated))
                    dao.getCursor("tags") shouldBe updated
                } finally {
                    db.close()
                }
            }
        }

        test("cursors for different domains are independent") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val dao = db.syncCursorDao()
                    val tagsRevision = 10L
                    val booksRevision = 20L
                    dao.setCursor(SyncCursorEntity(domainName = "tags", revision = tagsRevision))
                    dao.setCursor(SyncCursorEntity(domainName = "books", revision = booksRevision))
                    dao.getCursor("tags") shouldBe tagsRevision
                    dao.getCursor("books") shouldBe booksRevision
                } finally {
                    db.close()
                }
            }
        }
    })
