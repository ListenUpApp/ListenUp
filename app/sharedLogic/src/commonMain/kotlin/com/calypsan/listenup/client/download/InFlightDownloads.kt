package com.calypsan.listenup.client.download

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The file downloads this process currently owns — running, or parked behind the concurrency cap.
 *
 * A row's database state cannot answer "is something already fetching this file?": a row left
 * DOWNLOADING by a process that was killed looks identical to one that is live. This is the live
 * half. The iOS download service asks it before starting a transfer, so a foreground cannot start a
 * second transfer of a file, and cancels through it, so a file still waiting for a slot never starts.
 */
internal class InFlightDownloads {
    private data class Owned(
        val bookId: String,
        val job: Job,
    )

    private val lock = reentrantLock()
    private val owned = mutableMapOf<String, Owned>()

    /** True while [audioFileId] has a running or parked transfer. */
    fun owns(audioFileId: String): Boolean = lock.withLock { audioFileId in owned }

    /**
     * Launch [action] for every item whose id is not already owned, at most [permits] at a time.
     * Every launched item is owned immediately, so the cap bounds execution, not enqueue.
     *
     * @return how many items were launched.
     */
    fun <T> launchNew(
        scope: CoroutineScope,
        permits: Semaphore,
        bookId: String,
        items: Iterable<T>,
        idOf: (T) -> String,
        action: suspend (T) -> Unit,
    ): Int {
        var launched = 0
        for (item in items) {
            val id = idOf(item)
            lock.withLock {
                if (id in owned) return@withLock
                // LAZY + register before start: a job can never finish before it is recorded.
                val job = scope.launch(start = CoroutineStart.LAZY) { permits.withPermit { action(item) } }
                owned[id] = Owned(bookId, job)
                // Identity check: a finishing old job must not remove a newer job's entry for the same id.
                job.invokeOnCompletion { lock.withLock { if (owned[id]?.job === job) owned.remove(id) } }
                job.start()
                launched++
            }
        }
        return launched
    }

    /** Cancel every running and parked transfer of [bookId]. Returns how many were cancelled. */
    fun cancelBook(bookId: String): Int {
        val jobs = lock.withLock { owned.values.filter { it.bookId == bookId }.map { it.job } }
        jobs.forEach { it.cancel() }
        return jobs.size
    }

    /** Cancel everything and wait until every transfer has actually stopped. */
    suspend fun cancelAllAndJoin() {
        val jobs = lock.withLock { owned.values.map { it.job } }
        jobs.forEach { it.cancel() }
        jobs.joinAll()
    }
}
