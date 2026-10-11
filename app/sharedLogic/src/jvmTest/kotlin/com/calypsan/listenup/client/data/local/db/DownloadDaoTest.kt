package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Regression tests for [DownloadDao] queries. Running against a real in-memory
 * [ListenUpDatabase] ensures Room's generated SQL and the type-converter round-trip
 * are both exercised.
 */
class DownloadDaoTest :
    FunSpec({
        fun entity(
            audioFileId: String,
            bookId: String = "book-1",
            state: DownloadState = DownloadState.QUEUED,
            startedAt: Long? = null,
        ) = DownloadEntity(
            audioFileId = audioFileId,
            bookId = bookId,
            filename = "$audioFileId.mp3",
            fileIndex = 0,
            state = state,
            localPath = null,
            totalBytes = 1000L,
            downloadedBytes = 0L,
            queuedAt = 0L,
            startedAt = startedAt,
            completedAt = null,
            errorMessage = null,
            retryCount = 0,
        )

        test("getIncomplete returns rows not COMPLETED and not DELETED") {
            val db = createInMemoryTestDatabase()
            val dao = db.downloadDao()
            try {
                runTest {
                    dao.insertAll(
                        listOf(
                            entity("file-1", state = DownloadState.QUEUED),
                            entity("file-2", state = DownloadState.DOWNLOADING),
                            entity("file-3", state = DownloadState.COMPLETED),
                            entity("file-4", state = DownloadState.DELETED),
                            entity("file-5", state = DownloadState.PAUSED),
                        ),
                    )
                    val incomplete = dao.getIncomplete()
                    // Expect: file-1, file-2, file-5 (QUEUED, DOWNLOADING, PAUSED)
                    // Reject: file-3 (COMPLETED), file-4 (DELETED)
                    incomplete.size shouldBe 3
                    incomplete.map { it.audioFileId }.toSet() shouldBe setOf("file-1", "file-2", "file-5")
                }
            } finally {
                db.close()
            }
        }

        test("markPausedIfNotTerminal is a no-op on terminal rows but pauses an active row (B7)") {
            val db = createInMemoryTestDatabase()
            val dao = db.downloadDao()
            try {
                runTest {
                    dao.insertAll(
                        listOf(
                            entity("file-cancelled", state = DownloadState.CANCELLED),
                            entity("file-deleted", state = DownloadState.DELETED),
                            entity("file-completed", state = DownloadState.COMPLETED),
                            entity("file-active", state = DownloadState.DOWNLOADING),
                        ),
                    )

                    // A late worker-cleanup pause must NOT clobber a terminal state.
                    dao.markPausedIfNotTerminal("file-cancelled")
                    dao.markPausedIfNotTerminal("file-deleted")
                    dao.markPausedIfNotTerminal("file-completed")
                    // ...but a genuinely in-flight row still pauses (e.g. auth-failure pause).
                    dao.markPausedIfNotTerminal("file-active")

                    val byId = dao.observeAll().first().associateBy { it.audioFileId }
                    byId.getValue("file-cancelled").state shouldBe DownloadState.CANCELLED
                    byId.getValue("file-deleted").state shouldBe DownloadState.DELETED
                    byId.getValue("file-completed").state shouldBe DownloadState.COMPLETED
                    byId.getValue("file-active").state shouldBe DownloadState.PAUSED
                }
            } finally {
                db.close()
            }
        }

        test("getIncomplete excludes FAILED rows past the retry budget but keeps retriable ones (B10b)") {
            val db = createInMemoryTestDatabase()
            val dao = db.downloadDao()
            try {
                runTest {
                    dao.insertAll(
                        listOf(
                            entity("failed-exhausted", state = DownloadState.FAILED).copy(retryCount = 3),
                            entity("failed-retriable", state = DownloadState.FAILED).copy(retryCount = 1),
                            entity("queued", state = DownloadState.QUEUED),
                            entity("downloading", state = DownloadState.DOWNLOADING),
                        ),
                    )

                    val incomplete = dao.getIncomplete()
                    // The exhausted FAILED row no longer churns on every startup; the retriable one still resumes.
                    incomplete.map { it.audioFileId }.toSet() shouldBe
                        setOf("failed-retriable", "queued", "downloading")
                }
            } finally {
                db.close()
            }
        }

        test("getLocalPath returns localPath for COMPLETED rows only") {
            val db = createInMemoryTestDatabase()
            val dao = db.downloadDao()
            try {
                runTest {
                    dao.insertAll(
                        listOf(
                            entity("file-1", state = DownloadState.COMPLETED).copy(localPath = "/path/to/file-1"),
                            entity("file-2", state = DownloadState.DOWNLOADING).copy(localPath = "/path/to/file-2"),
                        ),
                    )
                    dao.getLocalPath("file-1") shouldBe "/path/to/file-1"
                    // For non-COMPLETED rows, query should return null even if localPath is set.
                    dao.getLocalPath("file-2") shouldBe null
                }
            } finally {
                db.close()
            }
        }

        test("markDeletedForBook transitions all rows for a book to DELETED") {
            val db = createInMemoryTestDatabase()
            val dao = db.downloadDao()
            try {
                runTest {
                    dao.insertAll(
                        listOf(
                            entity("file-1", bookId = "book-1", state = DownloadState.COMPLETED)
                                .copy(localPath = "/path"),
                            entity("file-2", bookId = "book-1", state = DownloadState.DOWNLOADING),
                            entity("file-3", bookId = "book-2", state = DownloadState.QUEUED), // different book
                        ),
                    )
                    dao.markDeletedForBook("book-1")
                    val all = dao.observeAll().first()
                    val byId = all.associateBy { it.audioFileId }
                    byId.getValue("file-1").state shouldBe DownloadState.DELETED
                    byId.getValue("file-2").state shouldBe DownloadState.DELETED
                    byId.getValue("file-1").localPath shouldBe null // should be cleared
                    byId.getValue("file-3").state shouldBe DownloadState.QUEUED // book-2 unaffected
                }
            } finally {
                db.close()
            }
        }

        test("deleteDeletedRecordsForBook removes only DELETED rows, preserving COMPLETED downloads") {
            val db = createInMemoryTestDatabase()
            val dao = db.downloadDao()
            try {
                runTest {
                    dao.insertAll(
                        listOf(
                            entity("file-1", bookId = "book-1", state = DownloadState.COMPLETED)
                                .copy(localPath = "/path/to/file-1"),
                            entity("file-2", bookId = "book-1", state = DownloadState.DELETED),
                            entity("file-3", bookId = "book-1", state = DownloadState.QUEUED),
                            entity("file-4", bookId = "book-2", state = DownloadState.DELETED), // other book
                        ),
                    )

                    dao.deleteDeletedRecordsForBook("book-1")

                    val remaining = dao.observeAll().first().associateBy { it.audioFileId }
                    // Only book-1's DELETED tombstone is gone.
                    remaining.keys shouldBe setOf("file-1", "file-3", "file-4")
                    // The COMPLETED download and its local file path survive (offline copy stays playable).
                    remaining.getValue("file-1").state shouldBe DownloadState.COMPLETED
                    remaining.getValue("file-1").localPath shouldBe "/path/to/file-1"
                    dao.getLocalPath("file-1") shouldBe "/path/to/file-1"
                    // Other book's tombstone is untouched.
                    remaining.getValue("file-4").state shouldBe DownloadState.DELETED
                }
            } finally {
                db.close()
            }
        }

        test("hasDeletedRecords returns true if any DELETED row exists for a book") {
            val db = createInMemoryTestDatabase()
            val dao = db.downloadDao()
            try {
                runTest {
                    dao.insertAll(
                        listOf(
                            entity("file-1", bookId = "book-1", state = DownloadState.DELETED),
                            entity("file-2", bookId = "book-2", state = DownloadState.COMPLETED),
                        ),
                    )
                    dao.hasDeletedRecords("book-1") shouldBe true
                    dao.hasDeletedRecords("book-2") shouldBe false
                    dao.hasDeletedRecords("book-3") shouldBe false // non-existent book
                }
            } finally {
                db.close()
            }
        }

        test("claimForTransfer claims a startable row once") {
            val db = createInMemoryTestDatabase()
            val dao = db.downloadDao()
            try {
                runTest {
                    dao.insertAll(listOf(entity("file-1", state = DownloadState.QUEUED)))

                    dao.claimForTransfer("file-1", startedAt = 42L) shouldBe 1
                    val claimed = dao.getByAudioFileId("file-1")!!
                    claimed.state shouldBe DownloadState.DOWNLOADING
                    claimed.startedAt shouldBe 42L

                    dao.claimForTransfer("file-1", startedAt = 43L) shouldBe 0
                }
            } finally {
                db.close()
            }
        }

        test("claimForTransfer claims FAILED and PAUSED rows") {
            val db = createInMemoryTestDatabase()
            val dao = db.downloadDao()
            try {
                runTest {
                    dao.insertAll(
                        listOf(
                            entity("file-failed", state = DownloadState.FAILED),
                            entity("file-paused", state = DownloadState.PAUSED),
                        ),
                    )

                    dao.claimForTransfer("file-failed", startedAt = 1L) shouldBe 1
                    dao.claimForTransfer("file-paused", startedAt = 1L) shouldBe 1
                }
            } finally {
                db.close()
            }
        }

        test("claimForTransfer refuses COMPLETED, CANCELLED, DELETED and DOWNLOADING rows") {
            val db = createInMemoryTestDatabase()
            val dao = db.downloadDao()
            try {
                runTest {
                    val refused =
                        listOf(
                            DownloadState.COMPLETED,
                            DownloadState.CANCELLED,
                            DownloadState.DELETED,
                            DownloadState.DOWNLOADING,
                        )
                    dao.insertAll(refused.map { entity("file-$it", state = it) })

                    refused.forEach { state ->
                        dao.claimForTransfer("file-$state", startedAt = 1L) shouldBe 0
                        dao.getByAudioFileId("file-$state")!!.state shouldBe state
                    }
                    dao.claimForTransfer("no-such-file", startedAt = 1L) shouldBe 0
                }
            } finally {
                db.close()
            }
        }

        test("markFailedIfNotTerminal fails an active row and bumps retryCount") {
            val db = createInMemoryTestDatabase()
            val dao = db.downloadDao()
            try {
                runTest {
                    dao.insertAll(listOf(entity("file-1", state = DownloadState.DOWNLOADING)))

                    dao.markFailedIfNotTerminal("file-1", "boom")

                    val row = dao.getByAudioFileId("file-1")!!
                    row.state shouldBe DownloadState.FAILED
                    row.errorMessage shouldBe "boom"
                    row.retryCount shouldBe 1
                }
            } finally {
                db.close()
            }
        }

        test("markFailedIfNotTerminal leaves CANCELLED, DELETED and COMPLETED rows alone") {
            val db = createInMemoryTestDatabase()
            val dao = db.downloadDao()
            try {
                runTest {
                    val terminal = listOf(DownloadState.CANCELLED, DownloadState.DELETED, DownloadState.COMPLETED)
                    dao.insertAll(terminal.map { entity("file-$it", state = it) })

                    terminal.forEach { state ->
                        dao.markFailedIfNotTerminal("file-$state", "late error")
                        val row = dao.getByAudioFileId("file-$state")!!
                        row.state shouldBe state
                        row.errorMessage shouldBe null
                        row.retryCount shouldBe 0
                    }
                }
            } finally {
                db.close()
            }
        }

        test("updateStateIfTransferring moves between QUEUED and DOWNLOADING only") {
            val db = createInMemoryTestDatabase()
            val dao = db.downloadDao()
            try {
                runTest {
                    dao.insertAll(
                        listOf(
                            entity("file-queued", state = DownloadState.QUEUED),
                            entity("file-live", state = DownloadState.DOWNLOADING),
                            entity("file-cancelled", state = DownloadState.CANCELLED),
                        ),
                    )

                    dao.updateStateIfTransferring("file-queued", DownloadState.DOWNLOADING)
                    dao.updateStateIfTransferring("file-live", DownloadState.QUEUED)
                    dao.updateStateIfTransferring("file-cancelled", DownloadState.DOWNLOADING)

                    dao.getByAudioFileId("file-queued")!!.state shouldBe DownloadState.DOWNLOADING
                    dao.getByAudioFileId("file-live")!!.state shouldBe DownloadState.QUEUED
                    dao.getByAudioFileId("file-cancelled")!!.state shouldBe DownloadState.CANCELLED
                }
            } finally {
                db.close()
            }
        }

        test("markCompleted does not overwrite a row the user cancelled or deleted") {
            val db = createInMemoryTestDatabase()
            val dao = db.downloadDao()
            try {
                runTest {
                    val terminal = listOf(DownloadState.CANCELLED, DownloadState.DELETED)
                    dao.insertAll(terminal.map { entity("file-$it", state = it) })

                    terminal.forEach { state ->
                        dao.markCompleted("file-$state", "/late/file.mp3", completedAt = 99L) shouldBe 0
                        val row = dao.getByAudioFileId("file-$state")!!
                        row.state shouldBe state
                        row.localPath shouldBe null
                        row.completedAt shouldBe null
                    }
                }
            } finally {
                db.close()
            }
        }

        test("markCompleted completes a DOWNLOADING or QUEUED row") {
            val db = createInMemoryTestDatabase()
            val dao = db.downloadDao()
            try {
                runTest {
                    val live = listOf(DownloadState.DOWNLOADING, DownloadState.QUEUED)
                    dao.insertAll(live.map { entity("file-$it", state = it) })

                    live.forEach { state ->
                        dao.markCompleted("file-$state", "/done/file.mp3", completedAt = 99L) shouldBe 1
                        val row = dao.getByAudioFileId("file-$state")!!
                        row.state shouldBe DownloadState.COMPLETED
                        row.localPath shouldBe "/done/file.mp3"
                        row.completedAt shouldBe 99L
                    }
                }
            } finally {
                db.close()
            }
        }
    })
