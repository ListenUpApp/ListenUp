package com.calypsan.listenup.server.sidecar

import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.test.runTest

/**
 * Tests for [SidecarWriteStateRepository] — the thin SQLDelight wrapper over
 * `sidecar_write_state`, the round-trip discriminator table the [SidecarWriter]
 * (Task 4) and the [com.calypsan.listenup.server.scanner.sidecar.ListenUpSidecarReader]
 * (Task 5) both consult.
 */
class SidecarWriteStateRepositoryTest :
    FunSpec({

        test("save persists a row and findByBookId reads it back") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook(bookId = "book1")
                val repo = SidecarWriteStateRepository(sql)
                runTest {
                    repo.save(bookId = "book1", contentHashHex = "abc123", writtenAtMs = 1_000L)

                    val row = repo.findByBookId("book1")
                    row.shouldNotBeNull()
                    row.contentHashHex shouldBe "abc123"
                    row.writtenAtMs shouldBe 1_000L
                }
            }
        }

        test("save upserts — a second save for the same book replaces the prior row") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook(bookId = "book1")
                val repo = SidecarWriteStateRepository(sql)
                runTest {
                    repo.save(bookId = "book1", contentHashHex = "first", writtenAtMs = 1_000L)
                    repo.save(bookId = "book1", contentHashHex = "second", writtenAtMs = 2_000L)

                    val row = repo.findByBookId("book1")
                    row.shouldNotBeNull()
                    row.contentHashHex shouldBe "second"
                    row.writtenAtMs shouldBe 2_000L
                }
            }
        }

        test("deleteForBook clears the row") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook(bookId = "book1")
                val repo = SidecarWriteStateRepository(sql)
                runTest {
                    repo.save(bookId = "book1", contentHashHex = "abc123", writtenAtMs = 1_000L)

                    repo.deleteForBook("book1")

                    repo.findByBookId("book1").shouldBeNull()
                }
            }
        }

        test("findByBookId returns null for a book with no recorded write") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook(bookId = "book1")
                val repo = SidecarWriteStateRepository(sql)
                runTest {
                    repo.findByBookId("book1").shouldBeNull()
                }
            }
        }
    })
