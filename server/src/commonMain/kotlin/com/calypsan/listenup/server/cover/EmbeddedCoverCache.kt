package com.calypsan.listenup.server.cover

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedArtwork
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe in-memory LRU cache for embedded-artwork covers.
 *
 * Extracting embedded artwork means parsing the audio file — an I/O-bound
 * operation we never want to repeat for a hot cover. The cache stores the full
 * [EmbeddedArtwork] (mime + bytes), so the cover route gets the `Content-Type`
 * for free alongside the bytes.
 *
 * **Eviction.** A bounded access-order [LinkedHashMap] keeps the [maxSize]
 * most-recently-used entries and discards the rest. ~1000 covers at a few KB
 * each is a negligible heap footprint while comfortably covering a browsing
 * session's working set.
 *
 * **Single-flight loading.** [getOrCompute] runs its [loader] **exactly once**
 * per key even under concurrent access: a per-key [Mutex] serialises callers
 * racing for the same cover, so the second arrival sees the first arrival's
 * cached result rather than re-parsing. Distinct keys never contend.
 *
 * **Staleness caveat.** Entries are keyed by [BookId]. If a book's embedded
 * artwork changes after a rescan, the stale bytes remain cached until evicted.
 * Acceptable here — covers rarely change, and a server restart clears
 * the cache. Keying by cover hash would close the gap but would require the
 * route to thread the hash through; revisit if cover churn becomes a concern.
 *
 * @param maxSize the maximum number of cached covers; the least-recently-used
 *   entry is evicted once the cache would exceed it.
 */
class EmbeddedCoverCache(
    private val maxSize: Int = DEFAULT_MAX_SIZE,
) {
    private val mapLock = Mutex()
    private val keyLocks = HashMap<BookId, Mutex>()

    // kotlin.collections.LinkedHashMap is insertion-ordered on every platform; we make it
    // access-ordered by hand. read() re-inserts a hit at the tail (most-recently-used), and
    // write() evicts the eldest (first) key once size exceeds maxSize — dropping that key's
    // per-key Mutex in lockstep, so a cached key's lock never outlives its entry. Both run
    // under mapLock, so the entry map and the lock map shrink together race-free.
    private val entries = LinkedHashMap<BookId, EmbeddedArtwork>()

    /**
     * Returns the cached artwork for [key], or computes and caches it via
     * [loader].
     *
     * [loader] is invoked at most once per key: concurrent callers for the same
     * key serialise on a per-key lock, so only the first runs [loader] and the
     * rest observe its cached result. A `null` [loader] result (no artwork, or
     * extraction failed) is **not** cached — a later call retries the loader.
     *
     * @return the artwork, or `null` when [loader] yields `null`.
     */
    suspend fun getOrCompute(
        key: BookId,
        loader: suspend () -> EmbeddedArtwork?,
    ): EmbeddedArtwork? {
        read(key)?.let { return it }

        return keyLockFor(key).withLock {
            // Re-check under the per-key lock — a racing caller may have populated it.
            read(key)?.let { return@withLock it }
            loader()?.also { write(key, it) }
        }
    }

    private suspend fun read(key: BookId): EmbeddedArtwork? =
        mapLock.withLock {
            // Re-insert a hit at the tail so it counts as most-recently-used.
            entries.remove(key)?.also { entries[key] = it }
        }

    private suspend fun write(
        key: BookId,
        value: EmbeddedArtwork,
    ) = mapLock.withLock {
        entries.remove(key)
        entries[key] = value
        if (entries.size > maxSize) {
            val eldest = entries.keys.first()
            entries.remove(eldest)
            keyLocks.remove(eldest)
        }
    }

    private suspend fun keyLockFor(key: BookId): Mutex = mapLock.withLock { keyLocks.getOrPut(key) { Mutex() } }

    /**
     * Test hook: reports whether [keyLocks] still holds a per-key [Mutex] for
     * [key]. Lets a test assert the lock is evicted in lockstep with its
     * [entries] entry, proving the [keyLocks] map stays bounded.
     */
    internal suspend fun hasKeyLock(key: BookId): Boolean = mapLock.withLock { keyLocks.containsKey(key) }

    private companion object {
        const val DEFAULT_MAX_SIZE = 1000
    }
}
