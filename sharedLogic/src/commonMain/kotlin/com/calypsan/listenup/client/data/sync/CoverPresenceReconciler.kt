package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.core.Timestamp

/**
 * Startup self-heal for the `books.coverDownloadedAt` cover-presence marker.
 *
 * The marker is the single source of truth for local cover presence at mapping time
 * (no per-book filesystem stat), so it must converge with disk reality: covers deleted
 * externally, files written before the marker existed (v42→v43 upgraders), or a crash
 * between file write and row update. One directory listing + one SELECT reconciles
 * both directions; a no-op start touches zero rows and wakes no observers.
 */
internal class CoverPresenceReconciler(
    private val bookDao: BookDao,
    private val imageStorage: ImageStorage,
) {
    suspend fun reconcile() {
        val onDisk = imageStorage.listCoverBookIds()
        val marked = bookDao.idsWithCoverMarked().toSet()
        val now = Timestamp.now()
        (onDisk - marked).toList().chunked(CHUNK_SIZE).forEach { bookDao.markCoversDownloaded(it, now) }
        (marked - onDisk).toList().chunked(CHUNK_SIZE).forEach { bookDao.clearCoversDownloaded(it) }
    }

    private companion object {
        /** Stay far under SQLite's host-parameter limit for the IN (:ids) expansion. */
        const val CHUNK_SIZE = 500
    }
}
