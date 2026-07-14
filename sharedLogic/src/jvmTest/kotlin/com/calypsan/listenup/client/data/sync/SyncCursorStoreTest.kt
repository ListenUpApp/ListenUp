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

        test("setCursor is monotonic — a lower revision never rewinds the stored cursor") {
            runTest {
                val db = createInMemoryTestDatabase()
                val store = SyncCursorStore(db.syncCursorDao())
                store.setCursor("tags", 5L)
                // A buffered pre-disconnect frame lands after a catch-up already advanced further.
                store.setCursor("tags", 3L)
                store.getCursor("tags") shouldBe 5L
                // A genuinely-higher revision still advances.
                store.setCursor("tags", 8L)
                store.getCursor("tags") shouldBe 8L
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
