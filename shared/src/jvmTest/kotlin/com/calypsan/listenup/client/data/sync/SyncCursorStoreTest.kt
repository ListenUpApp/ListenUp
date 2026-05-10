package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class SyncCursorStoreTest :
    FunSpec({

        test("getCursor returns null when nothing has been stored") {
            runTest {
                val db = createInMemoryTestDatabase()
                val store = SyncCursorStore(db.syncCursorDao())
                store.getCursor("tags") shouldBe null
                db.close()
            }
        }

        test("setCursor + getCursor round-trip") {
            runTest {
                val db = createInMemoryTestDatabase()
                val store = SyncCursorStore(db.syncCursorDao())
                val expected = 42L
                store.setCursor("tags", expected)
                store.getCursor("tags") shouldBe expected
                db.close()
            }
        }

        test("highestCursor returns max across all domains, or null when empty") {
            runTest {
                val db = createInMemoryTestDatabase()
                val store = SyncCursorStore(db.syncCursorDao())
                store.highestCursor() shouldBe null
                val tags = 10L
                val books = 50L
                val series = 30L
                store.setCursor("tags", tags)
                store.setCursor("books", books)
                store.setCursor("series", series)
                store.highestCursor() shouldBe books
                db.close()
            }
        }
    })
