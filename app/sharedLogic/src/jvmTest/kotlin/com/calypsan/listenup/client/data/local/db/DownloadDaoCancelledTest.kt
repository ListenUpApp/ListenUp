package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Regression test: a CANCELLED download must be excluded from [DownloadDao.getIncomplete]
 * so that [resumeIncompleteDownloads] does not silently restart a user-cancelled download
 * on the next app launch.
 */
class DownloadDaoCancelledTest :
    FunSpec({
        val db = createInMemoryTestDatabase()
        val dao = db.downloadDao()

        afterSpec { db.close() }

        fun entity(
            audioFileId: String,
            state: DownloadState,
        ) = DownloadEntity(
            audioFileId = audioFileId,
            bookId = "book-1",
            filename = "$audioFileId.mp3",
            fileIndex = 0,
            state = state,
            localPath = null,
            totalBytes = 1000L,
            downloadedBytes = 0L,
            queuedAt = 0L,
            startedAt = null,
            completedAt = null,
            errorMessage = null,
            retryCount = 0,
        )

        test("getIncomplete excludes CANCELLED rows") {
            dao.insertAll(
                listOf(
                    entity("file-paused", DownloadState.PAUSED),
                    entity("file-cancelled", DownloadState.CANCELLED),
                ),
            )

            val incomplete = dao.getIncomplete()

            incomplete.map { it.audioFileId } shouldContainExactlyInAnyOrder listOf("file-paused")
            incomplete.size shouldBe 1
        }
    })
