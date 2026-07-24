package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Tests for [CoverPresenceReconciler.reconcile] — the startup self-heal that converges the
 * `books.coverDownloadedAt` marker with the on-disk covers directory.
 */
class CoverPresenceReconcilerTest :
    FunSpec({

        fun reconciler(
            bookDao: BookDao,
            imageStorage: ImageStorage,
        ) = CoverPresenceReconciler(bookDao = bookDao, imageStorage = imageStorage)

        test("marks ids found on disk but not marked, and clears ids marked but missing on disk") {
            runTest {
                val imageStorage =
                    mock<ImageStorage> {
                        every { listCoverBookIds() } returns setOf(BookId("a"), BookId("b"))
                    }
                val bookDao =
                    mock<BookDao> {
                        everySuspend { idsWithCoverMarked() } returns listOf(BookId("b"), BookId("c"))
                        everySuspend { markCoversDownloaded(any(), any()) } returns Unit
                        everySuspend { clearCoversDownloaded(any()) } returns Unit
                    }

                reconciler(bookDao, imageStorage).reconcile()

                // "a" is on disk but not marked → gets marked.
                verifySuspend { bookDao.markCoversDownloaded(listOf(BookId("a")), any()) }
                // "c" is marked but missing on disk → gets cleared.
                verifySuspend { bookDao.clearCoversDownloaded(listOf(BookId("c"))) }
            }
        }

        test("does nothing when disk and the marker already agree") {
            runTest {
                val imageStorage =
                    mock<ImageStorage> {
                        every { listCoverBookIds() } returns setOf(BookId("a"), BookId("b"))
                    }
                val bookDao =
                    mock<BookDao> {
                        everySuspend { idsWithCoverMarked() } returns listOf(BookId("a"), BookId("b"))
                    }

                reconciler(bookDao, imageStorage).reconcile()

                // No drift → neither batch call runs. A strict mock without these stubs would
                // throw if the reconciler called them, so this also guards against a spurious
                // no-op write that would wake Room observers for nothing.
                verifySuspend(VerifyMode.not) { bookDao.markCoversDownloaded(any(), any()) }
                verifySuspend(VerifyMode.not) { bookDao.clearCoversDownloaded(any()) }
            }
        }

        test("chunks a large mark batch so no single call covers more than the chunk size") {
            runTest {
                val onDiskIds = (1..1200).map { BookId("book-$it") }.toSet()
                val imageStorage = mock<ImageStorage> { every { listCoverBookIds() } returns onDiskIds }
                val seenChunks = mutableListOf<List<BookId>>()
                val bookDao =
                    mock<BookDao> {
                        everySuspend { idsWithCoverMarked() } returns emptyList()
                        everySuspend { markCoversDownloaded(any(), any()) } calls { args ->
                            @Suppress("UNCHECKED_CAST")
                            seenChunks += args.args[0] as List<BookId>
                        }
                    }

                reconciler(bookDao, imageStorage).reconcile()

                // 1200 ids at a 500-per-call chunk size means at least 3 calls, no chunk exceeds
                // the limit, and every id is covered across the chunks exactly once.
                seenChunks.size shouldBe 3
                seenChunks.forEach { chunk -> (chunk.size <= CHUNK_SIZE_UNDER_TEST) shouldBe true }
                seenChunks.flatten().toSet() shouldBe onDiskIds
            }
        }
    }) {
    private companion object {
        /** Mirrors [CoverPresenceReconciler]'s private chunk size — kept in sync intentionally. */
        const val CHUNK_SIZE_UNDER_TEST = 500
    }
}
